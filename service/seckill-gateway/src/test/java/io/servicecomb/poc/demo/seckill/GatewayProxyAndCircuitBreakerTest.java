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

import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.servicecomb.poc.demo.GatewayApplication;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * 守护网关转发和失败时的回退：查询成功原样返回，业务上的 429 原样透传，下游 500 则变成 503。
 * <p>
 * {@code seckill.gateway.rate-limiter=memory} 使用内存限流。三个上游地址都指向同一个 {@link MockWebServer}。
 * 没有 Kafka、Elasticsearch 或 H2。
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayProxyAndCircuitBreakerTest {

  private static final MockWebServer downstream = new MockWebServer();

  static {
    try {
      downstream.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void registerDownstream(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + downstream.getPort();
    registry.add("seckill.gateway.admin-uri", () -> base);
    registry.add("seckill.gateway.command-uri", () -> base);
    registry.add("seckill.gateway.query-uri", () -> base);
    registry.add("seckill.gateway.rate-limiter", () -> "memory");
  }

  @AfterAll
  static void shutdown() throws IOException {
    downstream.shutdown();
  }

  @Autowired
  private WebTestClient webTestClient;

  /**
   * 前置：下游对下一次请求回答 200，正文 ok。
   * 动作：GET /query/promotions。
   * 期望：网关也是 HTTP 200，正文仍是 ok。
   */
  @Test
  void proxiesSuccessfulQuery() {
    downstream.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/plain")
        .setBody("ok"));

    webTestClient.get()
        .uri("/query/promotions")
        .exchange()
        .expectStatus().isOk()
        .expectBody(String.class).isEqualTo("ok");
  }

  /**
   * 前置：下游对抢券回答 429，正文 out of stock。
   * 动作：POST /command/coupons/。
   * 期望：网关原样返回 HTTP 429 和这段正文，不改成熔断文案。
   */
  @Test
  void passesThroughBusinessTooManyRequests() {
    downstream.enqueue(new MockResponse()
        .setResponseCode(429)
        .setHeader("Content-Type", "text/plain")
        .setBody("out of stock"));

    webTestClient.post()
        .uri("/command/coupons/")
        .exchange()
        .expectStatus().isEqualTo(429)
        .expectBody(String.class).isEqualTo("out of stock");
  }

  /**
   * 前置：下游对查询回答 500。
   * 动作：GET /query/promotions。
   * 期望：网关返回 HTTP 503，正文是 query unavailable。
   */
  @Test
  void fallsBackWhenDownstreamReturnsServerError() {
    downstream.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

    webTestClient.get()
        .uri("/query/promotions")
        .exchange()
        .expectStatus().isEqualTo(503)
        .expectBody(String.class).isEqualTo("query unavailable");
  }
}
