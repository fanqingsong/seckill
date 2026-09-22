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
