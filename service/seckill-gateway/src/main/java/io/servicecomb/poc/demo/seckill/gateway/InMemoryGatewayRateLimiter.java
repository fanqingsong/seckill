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

/**
 * 进程内的令牌桶限流器。测试和默认配置用它，计数不进 Redis。
 * <p>
 * {@link GatewayRateLimiterConfiguration} 在 {@code seckill.gateway.rate-limiter} 缺省或等于
 * {@code memory} 时创建本类，Bean 名仍是 {@code redisRateLimiter}，这样路由配置不用分两套。
 * 它实现 Spring Cloud Gateway 的 {@link RateLimiter}，泛型参数用 {@code RedisRateLimiter.Config}，
 * 是为了和 Redis 实现共用同一份速率配置，本类并不连接 Redis。
 * <p>
 * 多实例部署时每个进程各计各的。需要全集群共用计数时，把配置改成 {@code redis}。
 */
public class InMemoryGatewayRateLimiter implements RateLimiter<RedisRateLimiter.Config> {

  private final Map<String, RedisRateLimiter.Config> config = new ConcurrentHashMap<>();
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();
  private final RedisRateLimiter.Config defaultConfig = new RedisRateLimiter.Config()
      .setReplenishRate(1)
      .setBurstCapacity(1)
      .setRequestedTokens(1);

  /**
   * 判断这一次请求能否通过。{@code @Override} 表示实现接口方法。
   * <p>
   * {@code synchronized} 锁住「路由 + 客户端」这一把锁，避免两个请求同时改同一个桶。
   * 允许时扣令牌；不允许时令牌不动，Gateway 会拒绝这次转发。
   *
   * @param routeId 路由 id，例如 {@code command}
   * @param id 限流键，这里是客户端 IP
   * @return {@code allowed} 为真才继续转发；响应头里带剩余令牌，方便对照 Redis 限流器的头
   */
  @Override
  public Mono<Response> isAllowed(String routeId, String id) {
    RedisRateLimiter.Config routeConfig = config.getOrDefault(routeId, defaultConfig);
    // 配置缺失或写成 0 时，至少按每秒 1 个、桶容量 1、每次消耗 1 来算，避免除零或负速率。
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
      // 按流逝时间补令牌，但不超过桶容量。
      bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * replenish);
      bucket.lastNanos = now;
      allowed = bucket.tokens >= requested;
      // 令牌够：扣掉本次消耗。不够：保持原样，调用方看到 allowed=false。
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

  /**
   * 路由 id 到速率配置的表。{@link GatewayConfiguration} 启动时往里放 admin、command、query。
   *
   * @return 可变的配置 Map，不是一份拷贝
   */
  @Override
  public Map<String, RedisRateLimiter.Config> getConfig() {
    return config;
  }

  /** 告诉 Gateway 配置对象的类型，以便绑定属性。 */
  @Override
  public Class<RedisRateLimiter.Config> getConfigClass() {
    return RedisRateLimiter.Config.class;
  }

  /**
   * 新建一份空的速率配置，字段由调用方再填。
   *
   * @return 尚未设置速率的配置对象
   */
  @Override
  public RedisRateLimiter.Config newConfig() {
    return new RedisRateLimiter.Config();
  }

  /** 一个限流键对应的桶：当前令牌和上次补令牌的时间。 */
  private static final class Bucket {
    private double tokens;
    private long lastNanos = System.nanoTime();

    /** 新桶一开始是满的，避免进程刚启动时把正常流量全部拒绝。 */
    private Bucket(int burst) {
      this.tokens = burst;
    }
  }
}
