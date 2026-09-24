package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionFinishEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.redis.GrabAttempt;
import io.servicecomb.poc.demo.seckill.redis.GrabToken;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SecKillCommandService<T> {

  private static final Logger logger = LoggerFactory.getLogger(SecKillCommandService.class);

  private final PromotionEntity promotion;
  private final SecKillStore store;
  private final TransactionalEventOutboxWriter writer;
  private final SecKillEventFormat eventFormat;
  private final AtomicBoolean finished = new AtomicBoolean();

  public SecKillCommandService(PromotionEntity promotion, SecKillStore store,
      TransactionalEventOutboxWriter writer, SecKillEventFormat eventFormat, boolean alreadyFinished) {
    this.promotion = promotion;
    this.store = store;
    this.writer = writer;
    this.eventFormat = eventFormat;
    this.finished.set(alreadyFinished);
  }

  public PromotionEntity getPromotion() {
    return promotion;
  }

  public SecKillGrabResult addCouponTo(T customerId) {
    if (finished.get() || promotion.getFinishTime().getTime() < System.currentTimeMillis()) {
      return SecKillGrabResult.Failed;
    }
    String customer = String.valueOf(customerId);
    GrabAttempt attempt = store.tryGrab(promotion.getPromotionId(), customer);
    if (attempt.isDuplicate()) {
      return SecKillGrabResult.Duplicate;
    }
    if (!attempt.isSuccess()) {
      return SecKillGrabResult.Failed;
    }
    return SecKillGrabResult.Success;
  }

  @SuppressWarnings("unchecked")
  public void persistGrab(GrabToken token) {
    CouponGrabbedEvent<T> event = new CouponGrabbedEvent<T>(promotion, (T) token.getCustomerId());
    EventMessageDto message = eventFormat.toMessage(event, UUID.randomUUID().toString(), token.getSeq());
    writer.persist(message);
  }

  public void finish() {
    if (finished.get() || writer.hasFinishEvent(promotion.getPromotionId())) {
      finished.set(true);
      return;
    }
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    try {
      PromotionFinishEvent event = new PromotionFinishEvent(promotion);
      long seq = store.nextSeq(promotion.getPromotionId());
      writer.persist(eventFormat.toMessage(event, UUID.randomUUID().toString(), seq));
    } catch (RuntimeException e) {
      logger.warn("Failed to persist finish event {}", promotion.getPromotionId(), e);
      finished.set(false);
    }
  }
}
