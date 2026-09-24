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

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 测试用的异常处理片段：把抛出的异常变成 HTTP 429。
 * <p>
 * 业务代码里限流失败会抛异常，Spring MVC 默认不一定按我们要的状态码写出响应。
 * 测试把本类交给 {@link InvocationExceptionHandlerExceptionResolver}，由它在状态码已是 429 时调用这里。
 * {@code @ExceptionHandler(Exception.class)} 表示这个方法处理异常；
 * {@code @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)} 把响应状态固定为 429 Too Many Requests。
 * 本类不参与真实 Gateway 限流，也不写业务数据。
 */
class TooManyRequestsExceptionAdvice {

  /**
   * 把异常信息放进响应体。
   *
   * @param e 测试里抛出的异常，消息会成为正文
   * @return 包着异常消息的 HTTP 实体，状态码由类上的注解决定为 429
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  public Object exceptionHandler(Exception e) {
    return new HttpEntity<>(e.getMessage());
  }
}
