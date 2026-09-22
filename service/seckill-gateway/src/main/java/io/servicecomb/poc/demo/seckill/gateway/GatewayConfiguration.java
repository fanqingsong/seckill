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

package io.servicecomb.poc.demo.seckill.gateway;

import java.net.InetSocketAddress;
import java.util.Set;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfiguration {

  static final Set<String> DOWNSTREAM_FAILURE_STATUSES = Set.of("500", "502", "503", "504");

  @Bean
  KeyResolver ipKeyResolver() {
    return exchange -> {
      HttpHeaders headers = exchange.getRequest().getHeaders();
      String forwarded = headers.getFirst("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        return Mono.just(forwarded.split(",")[0].trim());
      }
      String realIp = headers.getFirst("X-Real-IP");
      if (realIp != null && !realIp.isBlank()) {
        return Mono.just(realIp);
      }
      InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
      return Mono.just(remote == null || remote.getAddress() == null
          ? "unknown"
          : remote.getAddress().getHostAddress());
    };
  }

  @Bean
  RouteLocator seckillRoutes(RouteLocatorBuilder builder, KeyResolver ipKeyResolver,
      RateLimiter<RedisRateLimiter.Config> redisRateLimiter, SecKillGatewayProperties properties) {
    applyRouteLimits(redisRateLimiter, properties);
    return builder.routes()
        .route("admin", r -> r.path("/admin/**")
            .filters(f -> f.requestRateLimiter(c -> {
              c.setKeyResolver(ipKeyResolver);
              c.setRateLimiter(redisRateLimiter);
            }).circuitBreaker(c -> {
              c.setName("adminCb");
              c.setFallbackUri("forward:/fallback/admin");
              c.setStatusCodes(DOWNSTREAM_FAILURE_STATUSES);
            }))
            .uri(properties.getAdminUri()))
        .route("command", r -> r.path("/command/**")
            .filters(f -> f.requestRateLimiter(c -> {
              c.setKeyResolver(ipKeyResolver);
              c.setRateLimiter(redisRateLimiter);
            }).circuitBreaker(c -> {
              c.setName("commandCb");
              c.setFallbackUri("forward:/fallback/command");
              c.setStatusCodes(DOWNSTREAM_FAILURE_STATUSES);
            }))
            .uri(properties.getCommandUri()))
        .route("query", r -> r.path("/query/**")
            .filters(f -> f.requestRateLimiter(c -> {
              c.setKeyResolver(ipKeyResolver);
              c.setRateLimiter(redisRateLimiter);
            }).circuitBreaker(c -> {
              c.setName("queryCb");
              c.setFallbackUri("forward:/fallback/query");
              c.setStatusCodes(DOWNSTREAM_FAILURE_STATUSES);
            }))
            .uri(properties.getQueryUri()))
        .build();
  }

  private static void applyRouteLimits(RateLimiter<RedisRateLimiter.Config> limiter,
      SecKillGatewayProperties properties) {
    limiter.getConfig().put("admin", config(limiter, properties.getAdmin()));
    limiter.getConfig().put("command", config(limiter, properties.getCommand()));
    limiter.getConfig().put("query", config(limiter, properties.getQuery()));
  }

  private static RedisRateLimiter.Config config(RateLimiter<RedisRateLimiter.Config> limiter,
      SecKillGatewayProperties.Limit limit) {
    RedisRateLimiter.Config cfg = limiter.newConfig();
    cfg.setReplenishRate(limit.getReplenishRate());
    cfg.setBurstCapacity(limit.getBurstCapacity());
    cfg.setRequestedTokens(1);
    return cfg;
  }
}
