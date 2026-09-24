/*
 *   Copyright 2017 Huawei Technologies Co., Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.servicecomb.poc.demo.seckill;

import java.lang.reflect.Method;

import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * 测试里替换 Spring MVC 的异常解析器，把 400 和 429 交给固定的处理类。
 * <p>
 * 生产控制器抛出 {@link ResponseStatusException} 时，默认解析器不一定走到我们期望的响应体。
 * 测试配置注册本类后，Spring 在找「用哪个方法处理这个异常」时会进到这里。
 * {@code extends ExceptionHandlerExceptionResolver} 表示沿用 Spring 的解析流程，只改选择处理方法这一步。
 * {@code @Override} 的方法签名必须和父类一致。
 * <p>
 * 400 使用 {@link BadRequestExceptionAdvice}，429 使用 {@link TooManyRequestsExceptionAdvice}。
 * 其它异常仍交给父类。本类不写业务数据，只决定测试里的 HTTP 状态和正文。
 */
public class InvocationExceptionHandlerExceptionResolver extends ExceptionHandlerExceptionResolver {

  /**
   * 按异常状态码挑选测试用的处理方法。
   *
   * @param handlerMethod 抛出异常的控制器方法，本实现在 400 和 429 时不使用它
   * @param exception 控制器抛出的异常
   * @return 要执行的处理方法和它所在的对象；不是 400/429 时用父类原来的选择
   */
  @Override
  protected ServletInvocableHandlerMethod getExceptionHandlerMethod(final HandlerMethod handlerMethod,
      final Exception exception) {
    // 不是带 HTTP 状态的异常：仍按 Spring 默认规则找 @ExceptionHandler。
    if (!(exception instanceof ResponseStatusException)) {
      return super.getExceptionHandlerMethod(handlerMethod, exception);
    }
    ResponseStatusException statusException = (ResponseStatusException) exception;
    // 校验失败：固定走 400 的测试处理类，响应体是异常消息。
    if (statusException.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
      Method method = new ExceptionHandlerMethodResolver(BadRequestExceptionAdvice.class).resolveMethod(exception);
      return new ServletInvocableHandlerMethod(new BadRequestExceptionAdvice(), method);
    } else if (statusException.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
      // 限流失败：固定走 429 的测试处理类。
      Method method = new ExceptionHandlerMethodResolver(TooManyRequestsExceptionAdvice.class).resolveMethod(exception);
      return new ServletInvocableHandlerMethod(new TooManyRequestsExceptionAdvice(), method);
    }
    // 其它状态码，例如 404：不改，交给父类。
    return super.getExceptionHandlerMethod(handlerMethod, exception);
  }
}
