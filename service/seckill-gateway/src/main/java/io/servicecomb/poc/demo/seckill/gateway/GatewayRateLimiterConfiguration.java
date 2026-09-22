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

@Configuration
public class GatewayRateLimiterConfiguration {

  @Bean(name = "redisRateLimiter")
  @ConditionalOnProperty(name = "seckill.gateway.rate-limiter", havingValue = "memory", matchIfMissing = true)
  RateLimiter<RedisRateLimiter.Config> inMemoryRateLimiter() {
    return new InMemoryGatewayRateLimiter();
  }

  @Configuration
  @ConditionalOnProperty(name = "seckill.gateway.rate-limiter", havingValue = "redis")
  static class RedisRateLimiterBeans {
    @Bean
    LettuceConnectionFactory redisConnectionFactory(SecKillGatewayProperties properties) {
      RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
          properties.getRedisHost(), properties.getRedisPort());
      LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone);
      factory.afterPropertiesSet();
      return factory;
    }

    @Bean
    ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory) {
      return new ReactiveStringRedisTemplate(factory);
    }

    @Bean(name = "redisRateLimiter")
    RedisRateLimiter redisRateLimiter() {
      return new RedisRateLimiter(1, 1, 1);
    }
  }
}
