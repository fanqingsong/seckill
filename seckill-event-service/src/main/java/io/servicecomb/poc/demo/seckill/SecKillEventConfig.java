package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.es.SecKillEsConfig;
import io.servicecomb.poc.demo.seckill.es.SecKillSearchIndex;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.kafka.InMemoryEventBus;
import io.servicecomb.poc.demo.seckill.kafka.KafkaSecKillEventConsumer;
import io.servicecomb.poc.demo.seckill.kafka.SecKillEventPublisher;
import io.servicecomb.poc.demo.seckill.kafka.SecKillKafkaConfig;
import io.servicecomb.poc.demo.seckill.redis.SecKillRedisConfig;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({SecKillRedisConfig.class, SecKillKafkaConfig.class, SecKillEsConfig.class})
public class SecKillEventConfig {

  @Bean
  EventProjector eventProjector(SecKillEventFormat eventFormat, SecKillStore store, SecKillSearchIndex searchIndex,
      SpringSecKillEventRepository eventRepository,
      @Value("${seckill.infra.mode:memory}") String mode,
      @Value("${seckill.kafka.bootstrap:127.0.0.1:9092}") String bootstrap,
      SecKillEventPublisher publisher) {
    EventProjector projector = new EventProjector(eventFormat, store, searchIndex, eventRepository);
    if ("prod".equals(mode)) {
      KafkaSecKillEventConsumer consumer = new KafkaSecKillEventConsumer(bootstrap, projector, publisher);
      Executors.newSingleThreadExecutor().submit(consumer);
    } else if (publisher instanceof InMemoryEventBus) {
      ((InMemoryEventBus) publisher).subscribe(projector);
    }
    return projector;
  }
}
