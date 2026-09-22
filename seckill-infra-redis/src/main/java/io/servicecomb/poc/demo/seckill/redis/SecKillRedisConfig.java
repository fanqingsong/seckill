package io.servicecomb.poc.demo.seckill.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Configuration
public class SecKillRedisConfig {

  @Bean
  public SecKillStore secKillStore(
      @Value("${seckill.infra.mode:memory}") String mode,
      @Value("${seckill.redis.host:127.0.0.1}") String host,
      @Value("${seckill.redis.port:6379}") int port) {
    if ("prod".equals(mode)) {
      JedisPoolConfig config = new JedisPoolConfig();
      config.setMaxTotal(64);
      return new JedisSecKillStore(new JedisPool(config, host, port));
    }
    return new InMemorySecKillStore();
  }
}
