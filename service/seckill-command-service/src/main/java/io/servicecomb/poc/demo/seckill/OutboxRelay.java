package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import io.servicecomb.poc.demo.seckill.kafka.SecKillEventPublisher;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringOutboxRepository;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

  private static final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);

  private final SpringOutboxRepository outboxRepository;
  private final SecKillEventPublisher publisher;

  public OutboxRelay(SpringOutboxRepository outboxRepository, SecKillEventPublisher publisher) {
    this.outboxRepository = outboxRepository;
    this.publisher = publisher;
    Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(this::publishPending, 200, 200,
        TimeUnit.MILLISECONDS);
  }

  void publishPending() {
    try {
      List<OutboxEntity> batch = outboxRepository.findTop50ByPublishedFalseOrderByIdAsc();
      for (OutboxEntity row : batch) {
        publisher.publish(row.getKafkaKey(), row.getPayload());
        row.markPublished();
        outboxRepository.save(row);
      }
    } catch (RuntimeException e) {
      logger.warn("Outbox relay failed: {}", e.getMessage());
    }
  }
}
