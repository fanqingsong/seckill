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

package io.servicecomb.poc.demo.seckill.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
public class GatewayFallbackController {

  @RequestMapping("/fallback/admin")
  public Mono<ResponseEntity<String>> admin() {
    return unavailable("admin unavailable");
  }

  @RequestMapping("/fallback/command")
  public Mono<ResponseEntity<String>> command() {
    return unavailable("command unavailable");
  }

  @RequestMapping("/fallback/query")
  public Mono<ResponseEntity<String>> query() {
    return unavailable("query unavailable");
  }

  private static Mono<ResponseEntity<String>> unavailable(String body) {
    return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.TEXT_PLAIN)
        .body(body));
  }
}
