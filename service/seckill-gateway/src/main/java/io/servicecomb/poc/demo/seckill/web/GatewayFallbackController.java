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

/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ GatewayFallbackController.java                  │
 * │ 链路：转发 · 熔断降级                           │
 * └─────────────────────────────────────────────────┘
 *
 * 下游 5xx 或熔断打开（forward:/fallback/*）
 * │
 * ▼
 * 【本文件】在 Gateway 进程内返回 HTTP 503
 * │
 * ▼
 * 纯文本；不再转发，也不写库存或券
 *
 * 一句话：抢券请求停在降级响应上时，Redis 库存不会被这次调用扣减。
 */

package io.servicecomb.poc.demo.seckill.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * 熔断打开或下游返回 5xx 时的本地响应。不转发，也不写业务数据。
 * <p>
 * {@code @RestController} 表示本类接收 HTTP。路由上的 {@code forward:/fallback/...} 把失败请求转到这里，
 * 浏览器拿到的是 503 和一行纯文本，而不是 Admin、Command、Query 的业务 JSON。
 * {@code Mono} 表示「稍后给出一个响应」；这里立刻用 {@code Mono.just} 放好结果，因为没有再去调用下游。
 */
@RestController
public class GatewayFallbackController {

  /**
   * Admin 路由的降级响应。路径 {@code /fallback/admin} 只在本 Gateway 进程内被 forward 到。
   *
   * @return HTTP 503，正文 {@code admin unavailable}
   */
  @RequestMapping("/fallback/admin")
  public Mono<ResponseEntity<String>> admin() {
    return unavailable("admin unavailable");
  }

  /**
   * Command 路由的降级响应。熔断打开时抢券请求停在这里，Redis 库存不会被这次调用扣减。
   *
   * @return HTTP 503，正文 {@code command unavailable}
   */
  @RequestMapping("/fallback/command")
  public Mono<ResponseEntity<String>> command() {
    return unavailable("command unavailable");
  }

  /**
   * Query 路由的降级响应。
   *
   * @return HTTP 503，正文 {@code query unavailable}
   */
  @RequestMapping("/fallback/query")
  public Mono<ResponseEntity<String>> query() {
    return unavailable("query unavailable");
  }

  /**
   * 组装 503 纯文本响应。
   *
   * @param body 告诉调用方是哪一条下游不可用
   * @return 已完成的响应，状态码 {@link HttpStatus#SERVICE_UNAVAILABLE}
   */
  private static Mono<ResponseEntity<String>> unavailable(String body) {
    return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.TEXT_PLAIN)
        .body(body));
  }
}
