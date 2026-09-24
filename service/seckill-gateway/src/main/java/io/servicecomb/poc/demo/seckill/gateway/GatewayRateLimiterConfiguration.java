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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 按配置选择限流计数放在进程内存还是 Redis。两种 Bean 不能同时存在，名字都叫 {@code redisRateLimiter}。
 * <p>
 * {@code @Configuration} 表示本类用来声明 Bean。{@code @ConditionalOnProperty} 看
 * {@code seckill.gateway.rate-limiter}：没写这个键，或值是 {@code memory}，装配 {@link InMemoryGatewayRateLimiter}。
 * 只有值正好是 {@code redis} 时，才创建下面的内部配置类，连接 {@link SecKillGatewayProperties} 里的 Redis，
 * 并用 Spring 自带的 {@link RedisRateLimiter} 做全集群计数。
 * <p>
 * Gateway 排除了 Redis 自动配置，所以 Redis 连接只在 {@code redis} 这一支里手工创建。限流 Redis 与业务库存 Redis
 * 可以是同一台，但这里的键只给网关计数，不保存券或库存。
 */
@Configuration
public class GatewayRateLimiterConfiguration {

  /**
   * 默认限流器：计数留在本进程。
   * <p>
   * {@code matchIfMissing = true} 表示配置文件没写 {@code seckill.gateway.rate-limiter} 时也创建这个 Bean。
   * {@code @Bean(name = "redisRateLimiter")} 固定名字，路由里按这个名字注入。
   *
   * @return 不访问 Redis 的限流器
   */
  @Bean(name = "redisRateLimiter")
  @ConditionalOnProperty(name = "seckill.gateway.rate-limiter", havingValue = "memory", matchIfMissing = true)
  RateLimiter<RedisRateLimiter.Config> inMemoryRateLimiter() {
    return new InMemoryGatewayRateLimiter();
  }

  /**
   * 仅当 {@code seckill.gateway.rate-limiter=redis} 时才加载。内部的 {@code @Bean} 同样由 Spring 创建。
   */
  @Configuration
  @ConditionalOnProperty(name = "seckill.gateway.rate-limiter", havingValue = "redis")
  static class RedisRateLimiterBeans {
    /**
     * 连接限流用的 Redis。Lettuce 是 Spring 默认的 Redis 客户端。
     * <p>
     * {@code afterPropertiesSet()} 立刻完成初始化，后面的模板才能拿连接。
     *
     * @param properties 提供 {@code seckill.gateway.redis-host} 和 {@code redis-port}
     * @return 已初始化的连接工厂
     */
    @Bean
    LettuceConnectionFactory redisConnectionFactory(SecKillGatewayProperties properties) {
      RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
          properties.getRedisHost(), properties.getRedisPort());
      LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone);
      factory.afterPropertiesSet();
      return factory;
    }

    /**
     * 响应式字符串模板。{@link RedisRateLimiter} 靠容器里的这个 Bean 读写计数，本方法不自己计数。
     *
     * @param factory 上一个 Bean 提供的连接
     * @return 给限流器使用的 Redis 模板
     */
    @Bean
    ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory) {
      return new ReactiveStringRedisTemplate(factory);
    }

    /**
     * 用 Redis 计数的限流器。构造参数 {@code (1, 1, 1)} 是默认补充速率、突发容量和每次消耗的令牌。
     * {@link GatewayConfiguration} 随后按路由覆盖成 properties 里的 admin、command、query 限额。
     *
     * @return 名字仍为 {@code redisRateLimiter} 的集群计数器
     */
    @Bean(name = "redisRateLimiter")
    RedisRateLimiter redisRateLimiter() {
      return new RedisRateLimiter(1, 1, 1);
    }
  }
}
