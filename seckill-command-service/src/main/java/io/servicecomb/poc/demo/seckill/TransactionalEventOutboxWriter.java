package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import io.servicecomb.poc.demo.seckill.event.SecKillEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringOutboxRepository;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalEventOutboxWriter implements SecKillEventPersistent {

  private final SpringSecKillEventRepository eventRepository;
  private final SpringOutboxRepository outboxRepository;
  private final SecKillEventFormat eventFormat;

  public TransactionalEventOutboxWriter(SpringSecKillEventRepository eventRepository,
      SpringOutboxRepository outboxRepository, SecKillEventFormat eventFormat) {
    this.eventRepository = eventRepository;
    this.outboxRepository = outboxRepository;
    this.eventFormat = eventFormat;
  }

  @Override
  public void persistEvent(SecKillEvent event) {
    persist(eventFormat.toMessage(event));
  }

  @Transactional
  public void persist(EventMessageDto message) {
    eventRepository.save(eventFormat.toEntity(message));
    outboxRepository.save(new OutboxEntity(message.getEventId(), message.getPromotionId(),
        eventFormat.getFormat().serialize(message)));
  }
}
