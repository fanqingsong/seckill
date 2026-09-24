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
 * 测试用的异常处理片段：把抛出的异常变成 HTTP 400。
 * <p>
 * 控制器校验失败时抛 {@code ResponseStatusException}。测试需要断言响应是 400 且正文是异常消息。
 * {@link InvocationExceptionHandlerExceptionResolver} 发现状态码是 400 时，构造本类并调用下面的方法。
 * {@code @ExceptionHandler(Exception.class)} 声明处理异常；
 * {@code @ResponseStatus(HttpStatus.BAD_REQUEST)} 把响应状态固定为 400。
 * 本类只存在于测试支持模块，不改生产请求怎么写库。
 */
class BadRequestExceptionAdvice {

  /**
   * 把异常信息放进响应体。
   *
   * @param e 测试里抛出的异常，消息会成为正文
   * @return 包着异常消息的 HTTP 实体，状态码由注解决定为 400
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Object exceptionHandler(Exception e) {
    return new HttpEntity<>(e.getMessage());
  }
}