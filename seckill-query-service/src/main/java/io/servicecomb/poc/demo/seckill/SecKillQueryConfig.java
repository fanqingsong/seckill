package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.es.SecKillEsConfig;
import io.servicecomb.poc.demo.seckill.es.SecKillSearchIndex;
import io.servicecomb.poc.demo.seckill.redis.SecKillRedisConfig;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({SecKillRedisConfig.class, SecKillEsConfig.class})
class SecKillQueryConfig {

  @Bean
  SecKillQueryService seckillQueryService(SecKillStore store, SecKillSearchIndex searchIndex) {
    return new SecKillQueryService(store, searchIndex);
  }
}
