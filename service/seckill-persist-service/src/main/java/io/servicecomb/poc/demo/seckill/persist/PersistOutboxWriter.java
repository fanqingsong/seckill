package io.servicecomb.poc.demo.seckill.persist;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.EventEntity;
import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.event.SecKillEventType;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringOutboxRepository;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PersistOutboxWriter {

  private final SpringSecKillEventRepository eventRepository;
  private final SpringOutboxRepository outboxRepository;
  private final SecKillEventFormat eventFormat;

  public PersistOutboxWriter(SpringSecKillEventRepository eventRepository,
      SpringOutboxRepository outboxRepository, SecKillEventFormat eventFormat) {
    this.eventRepository = eventRepository;
    this.outboxRepository = outboxRepository;
    this.eventFormat = eventFormat;
  }

  @Transactional
  public void persist(EventMessageDto message) {
    eventRepository.save(eventFormat.toEntity(message));
    outboxRepository.save(new OutboxEntity(message.getEventId(), message.getPromotionId(),
        eventFormat.getFormat().serialize(message)));
  }

  public boolean hasFinishEvent(String promotionId) {
    for (EventEntity event : eventRepository.findByPromotionId(promotionId)) {
      if (SecKillEventType.PromotionFinishEvent.equals(event.getType())) {
        return true;
      }
    }
    return false;
  }
}
