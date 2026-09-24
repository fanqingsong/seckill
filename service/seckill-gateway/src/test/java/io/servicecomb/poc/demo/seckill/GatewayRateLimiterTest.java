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
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.servicecomb.poc.demo.GatewayApplication;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * 守护网关对抢券路径的限流：突发容量用完后，下一笔请求不再转到下游。
 * <p>
 * {@code seckill.gateway.rate-limiter=memory} 表示计数在内存里，不连 Redis。
 * 下游是本机 {@link MockWebServer}，不是真正的 Command 服务。没有 Kafka、Elasticsearch 或 H2。
 * {@code @AutoConfigureWebTestClient} 提供 {@link WebTestClient}，用来对随机端口发请求。
 */
@SpringBootTest(
    classes = GatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "seckill.gateway.command.replenish-rate=1",
        "seckill.gateway.command.burst-capacity=1",
        "seckill.gateway.rate-limiter=memory"
    })
@AutoConfigureWebTestClient
class GatewayRateLimiterTest {

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
  }

  @AfterAll
  static void shutdown() throws IOException {
    downstream.shutdown();
  }

  @Autowired
  private WebTestClient webTestClient;

  /**
   * 前置：抢券限流的突发容量是 1，下游会回答 200 accepted。
   * 动作：连续两次 POST /command/coupons/。
   * 期望：第一次 HTTP 200 且正文是 accepted；第二次 HTTP 429，响应里带有剩余配额头。
   */
  @Test
  void rejectsWhenBurstExceeded() {
    downstream.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/plain")
        .setBody("accepted"));

    webTestClient.post()
        .uri("/command/coupons/")
        .exchange()
        .expectStatus().isOk()
        .expectBody(String.class).isEqualTo("accepted");

    webTestClient.post()
        .uri("/command/coupons/")
        .exchange()
        .expectStatus().isEqualTo(429)
        .expectHeader().exists(RedisRateLimiter.REMAINING_HEADER);
  }
}
