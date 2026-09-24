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

/**
 * 声明 Gateway 的转发规则、按 IP 限流和熔断。本类不写活动、库存或券。
 * <p>
 * {@code @Configuration} 表示这个类只声明 Bean。{@code @Bean} 方法的返回值会放进 Spring 容器。
 * 三条路由按路径把 {@code /admin/**}、{@code /command/**}、{@code /query/**} 转到
 * {@link SecKillGatewayProperties} 里的下游地址。限流器由 {@link GatewayRateLimiterConfiguration} 选择：
 * {@code seckill.gateway.rate-limiter} 缺省或为 {@code memory} 时用进程内计数，为 {@code redis} 时用 Redis。
 * 这里的参数类型写成 {@code RateLimiter<RedisRateLimiter.Config>}，两种实现都用同一套配置对象。
 * <p>
 * {@code Mono} 是响应式里的「稍后给出一个值」。Gateway 基于 WebFlux，限流键也用 {@code Mono<String>} 返回。
 */
@Configuration
public class GatewayConfiguration {

  /** 下游返回这些状态码时，熔断器把请求改走到本进程的 fallback，而不是把 5xx 原样交给浏览器。 */
  static final Set<String> DOWNSTREAM_FAILURE_STATUSES = Set.of("500", "502", "503", "504");

  /**
   * 用客户端 IP 作为限流键。同一 IP 共享一条路由上的令牌。
   * <p>
   * {@code exchange ->} 是 Lambda：Gateway 每来一个请求就调用这段代码。返回值是稍后可用的 IP 字符串。
   *
   * @return 限流键解析器，不访问业务存储
   */
  @Bean
  KeyResolver ipKeyResolver() {
    return exchange -> {
      HttpHeaders headers = exchange.getRequest().getHeaders();
      String forwarded = headers.getFirst("X-Forwarded-For");
      // 经过 nginx 时，最左边一截才是最初的客户端地址。
      if (forwarded != null && !forwarded.isBlank()) {
        return Mono.just(forwarded.split(",")[0].trim());
      }
      String realIp = headers.getFirst("X-Real-IP");
      // 没有转发链时，用反向代理单独放的真实 IP。
      if (realIp != null && !realIp.isBlank()) {
        return Mono.just(realIp);
      }
      InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
      // 直连且拿不到地址时，所有这类请求共用键 "unknown"。
      return Mono.just(remote == null || remote.getAddress() == null
          ? "unknown"
          : remote.getAddress().getHostAddress());
    };
  }

  /**
   * 组装三条转发路由。每条先限流，再熔断，最后转到对应服务。Gateway 本身不写 PostgreSQL、业务 Redis 或 Kafka。
   * <p>
   * {@code r.path(...).filters(...).uri(...)} 是路由 DSL：路径匹配后套过滤器，再转发到 URI。
   * {@code circuitBreaker} 的 {@code fallbackUri} 是 {@code forward:}，表示熔断打开或下游失败时，
   * 请求改发到本进程的 {@link io.servicecomb.poc.demo.seckill.web.GatewayFallbackController}，返回 503。
   *
   * @param builder Spring Cloud Gateway 提供的路由构造器
   * @param ipKeyResolver 上一个 Bean，按 IP 计数
   * @param redisRateLimiter 名字沿用配置类里的 Bean 名；实际可能是内存限流器
   * @param properties {@code seckill.gateway.*}，含下游地址和每条路由的速率
   * @return 启动后生效的路由表
   */
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
              // 下游 5xx 或熔断打开：不再把失败原样返回，改发到本进程 /fallback/admin。
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
              // 抢券下游不可用或熔断打开：改发到 /fallback/command，不写库存。
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
              // 查询下游不可用或熔断打开：改发到 /fallback/query。
              c.setName("queryCb");
              c.setFallbackUri("forward:/fallback/query");
              c.setStatusCodes(DOWNSTREAM_FAILURE_STATUSES);
            }))
            .uri(properties.getQueryUri()))
        .build();
  }

  /**
   * 把三条路由的令牌速率写进限流器。键 {@code admin}、{@code command}、{@code query} 与路由 id 相同，
   * Gateway 按路由 id 取配置。
   *
   * @param limiter 内存或 Redis 限流器，两者都暴露 {@code getConfig()}
   * @param properties 各路由的补充速率和突发容量
   */
  private static void applyRouteLimits(RateLimiter<RedisRateLimiter.Config> limiter,
      SecKillGatewayProperties properties) {
    limiter.getConfig().put("admin", config(limiter, properties.getAdmin()));
    limiter.getConfig().put("command", config(limiter, properties.getCommand()));
    limiter.getConfig().put("query", config(limiter, properties.getQuery()));
  }

  /**
   * 做一份限流配置。每次请求消耗 1 个令牌。
   *
   * @param limiter 用来 {@code newConfig()}，这样内存实现和 Redis 实现各自创建自己的配置对象
   * @param limit 这一条路由的补充速率和桶容量
   * @return 尚未放进限流器 Map 的配置
   */
  private static RedisRateLimiter.Config config(RateLimiter<RedisRateLimiter.Config> limiter,
      SecKillGatewayProperties.Limit limit) {
    RedisRateLimiter.Config cfg = limiter.newConfig();
    cfg.setReplenishRate(limit.getReplenishRate());
    cfg.setBurstCapacity(limit.getBurstCapacity());
    cfg.setRequestedTokens(1);
    return cfg;
  }
}
