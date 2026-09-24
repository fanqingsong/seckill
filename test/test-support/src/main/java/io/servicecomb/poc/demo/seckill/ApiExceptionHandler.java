/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ ApiExceptionHandler.java                        │
 * │ 链路：测试 · 状态异常转响应                     │
 * └─────────────────────────────────────────────────┘
 *
 * 控制器抛出 ResponseStatusException
 * │
 * ▼
 * 【本文件】用异常上的状态码和原因写 HTTP
 * │
 * ▼
 * MockMvc 可断言的响应（不访问 Redis / PostgreSQL / Kafka）
 *
 * 一句话：测试支持把 Spring MVC 异常变成对应的 HTTP 状态码。
 */

package io.servicecomb.poc.demo.seckill;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把 {@link ResponseStatusException} 写成对应的 HTTP 响应，供测试里的 Spring MVC 使用。
 * <p>
 * {@code @RestControllerAdvice} 表示 Spring 会把本类套到控制器外面：控制器抛出指定异常时，不走默认错误页，
 * 而调用这里的方法，返回值直接成为响应体。
 * {@code @ExceptionHandler(ResponseStatusException.class)} 只处理这一种异常。状态码用异常里已经带的码，
 * 正文用 {@code getReason()}。这样测试可以用 {@code MockMvc} 断言 400 或其它业务状态，而不必自己解析异常。
 * 本类不访问 Redis、PostgreSQL 或 Kafka。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /**
   * 按异常上的状态码和原因生成响应。
   *
   * @param exception 控制器抛出的状态异常，里面已经有 HTTP 状态和说明
   * @return 状态码与异常一致，正文是原因字符串；原因为空时正文也是空
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<String> handle(ResponseStatusException exception) {
    return ResponseEntity.status(exception.getStatusCode()).body(exception.getReason());
  }
}
