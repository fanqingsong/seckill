package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.kafka.SecKillKafkaConfig;
import io.servicecomb.poc.demo.seckill.redis.SecKillRedisConfig;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({SecKillRedisConfig.class, SecKillKafkaConfig.class})
class SecKillCommandConfig {

  @Bean
  Map<String, SecKillCommandService<String>> commandServices() {
    return new HashMap<String, SecKillCommandService<String>>();
  }

  @Bean
  SecKillPromotionBootstrap<String> secKillPromotionBootstrap(SpringPromotionRepository promotionRepository,
      Map<String, SecKillCommandService<String>> commandServices,
      SecKillRecoveryService<String> recoveryService,
      SecKillStore store,
      TransactionalEventOutboxWriter writer,
      SecKillEventFormat eventFormat) {
    SecKillPromotionBootstrap<String> promotionBootstrap = new SecKillPromotionBootstrap<String>(promotionRepository,
        commandServices, recoveryService, store, writer, eventFormat);
    promotionBootstrap.run();
    return promotionBootstrap;
  }

  @Bean
  SecKillRecoveryService<String> secKillRecoveryService(
      io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository eventRepository,
      SecKillEventFormat eventFormat) {
    return new SecKillRecoveryService<String>(eventRepository, eventFormat);
  }
}
