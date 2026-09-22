package io.servicecomb.poc.demo.seckill.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecKillKafkaConfig {

  @Bean
  public SecKillEventPublisher secKillEventPublisher(
      @Value("${seckill.infra.mode:memory}") String mode,
      @Value("${seckill.kafka.bootstrap:127.0.0.1:9092}") String bootstrap) {
    if ("prod".equals(mode)) {
      return new KafkaSecKillEventPublisher(bootstrap);
    }
    return new InMemoryEventBus();
  }
}
