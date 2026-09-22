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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;

import reactor.core.publisher.Mono;

public class InMemoryGatewayRateLimiter implements RateLimiter<RedisRateLimiter.Config> {

  private final Map<String, RedisRateLimiter.Config> config = new ConcurrentHashMap<>();
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  private final RedisRateLimiter.Config defaultConfig = new RedisRateLimiter.Config()
      .setReplenishRate(1)
      .setBurstCapacity(1)
      .setRequestedTokens(1);

  @Override
  public Mono<Response> isAllowed(String routeId, String id) {
    RedisRateLimiter.Config routeConfig = config.getOrDefault(routeId, defaultConfig);
    int replenish = Math.max(routeConfig.getReplenishRate(), 1);
    int burst = Math.max(routeConfig.getBurstCapacity(), 1);
    int requested = Math.max(routeConfig.getRequestedTokens(), 1);
    String key = routeId + ":" + id;
    long remaining;
    boolean allowed;
    synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
      Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(burst));
      long now = System.nanoTime();
      double elapsedSeconds = (now - bucket.lastNanos) / 1_000_000_000.0;
      bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * replenish);
      bucket.lastNanos = now;
      allowed = bucket.tokens >= requested;
      if (allowed) {
        bucket.tokens -= requested;
      }
      remaining = (long) bucket.tokens;
    }
    Map<String, String> headers = new HashMap<>();
    headers.put(RedisRateLimiter.REMAINING_HEADER, String.valueOf(Math.max(remaining, 0)));
    headers.put(RedisRateLimiter.REPLENISH_RATE_HEADER, String.valueOf(replenish));
    headers.put(RedisRateLimiter.BURST_CAPACITY_HEADER, String.valueOf(burst));
    headers.put(RedisRateLimiter.REQUESTED_TOKENS_HEADER, String.valueOf(requested));
    return Mono.just(new Response(allowed, headers));
  }

  @Override
  public Map<String, RedisRateLimiter.Config> getConfig() {
    return config;
  }

  @Override
  public Class<RedisRateLimiter.Config> getConfigClass() {
    return RedisRateLimiter.Config.class;
  }

  @Override
  public RedisRateLimiter.Config newConfig() {
    return new RedisRateLimiter.Config();
  }

  private static final class Bucket {
    private double tokens;
    private long lastNanos = System.nanoTime();

    private Bucket(int burst) {
      this.tokens = burst;
    }
  }
}
