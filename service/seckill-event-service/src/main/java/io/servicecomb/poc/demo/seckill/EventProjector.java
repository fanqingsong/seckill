package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.EventEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.es.SecKillSearchIndex;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionFinishEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionStartEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.event.SecKillEventType;
import io.servicecomb.poc.demo.seckill.kafka.SecKillEventListener;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventProjector implements SecKillEventListener {

  private static final Logger logger = LoggerFactory.getLogger(EventProjector.class);

  private final SecKillEventFormat eventFormat;
  private final SecKillStore store;
  private final SecKillSearchIndex searchIndex;
  private final SpringSecKillEventRepository eventRepository;

  public EventProjector(SecKillEventFormat eventFormat, SecKillStore store, SecKillSearchIndex searchIndex,
      SpringSecKillEventRepository eventRepository) {
    this.eventFormat = eventFormat;
    this.store = store;
    this.searchIndex = searchIndex;
    this.eventRepository = eventRepository;
  }

  @Override
  public void onEvent(String payload) {
    EventMessageDto message = eventFormat.getFormat().deserialize(payload, EventMessageDto.class);
    project(message);
  }

  public void project(EventMessageDto message) {
    long applied = store.appliedSeq(message.getPromotionId());
    if (message.getSeq() != 0 && message.getSeq() < applied) {
      return;
    }
    if (message.getSeq() != 0 && message.getSeq() > applied + 1) {
      store.buffer(message);
      fillGap(message.getPromotionId(), applied + 1);
      return;
    }
    apply(message);
    if (message.getSeq() != 0) {
      store.setAppliedSeq(message.getPromotionId(), message.getSeq());
    }
    drain(message.getPromotionId());
  }

  public void replay(String promotionId, long fromSeq) {
    List<EventEntity> events = eventRepository.findByPromotionIdAndSeqGreaterThanEqualOrderBySeqAsc(promotionId,
        fromSeq);
    for (EventEntity entity : events) {
      project(new EventMessageDto(entity.getEventId(), entity.getPromotionId(), entity.getSeq(), entity.getType(),
          entity.getOccurredAt(), entity.getContent(), entity.getCustomerId()));
    }
  }

  private void fillGap(String promotionId, long fromSeq) {
    try {
      replay(promotionId, fromSeq);
    } catch (RuntimeException e) {
      logger.warn("Gap fill failed for {} fromSeq={}", promotionId, fromSeq, e);
    }
  }

  private void drain(String promotionId) {
    for (EventMessageDto buffered : store.drainBuffer(promotionId)) {
      project(buffered);
    }
  }

  private void apply(EventMessageDto message) {
    SecKillEvent event = eventFormat.fromMessage(message);
    if (SecKillEventType.PromotionStartEvent.equals(event.getType())) {
      PromotionEntity promotion = ((PromotionStartEvent) event).getPromotion();
      store.saveActivePromotion(promotion);
      searchIndex.indexPromotion(promotion);
    } else if (SecKillEventType.CouponGrabbedEvent.equals(event.getType())) {
      @SuppressWarnings("unchecked")
      CouponEntity<String> coupon = ((CouponGrabbedEvent<String>) event).getCoupon();
      CouponEntity<String> saved = store.saveCoupon(coupon);
      searchIndex.indexCoupon(saved);
    } else if (SecKillEventType.PromotionFinishEvent.equals(event.getType())) {
      store.removeActivePromotion(event.getPromotionId());
      searchIndex.markPromotionFinished(event.getPromotionId());
    }
  }
}
