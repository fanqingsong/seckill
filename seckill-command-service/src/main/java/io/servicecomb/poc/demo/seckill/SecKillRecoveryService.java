package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.entities.EventEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.event.SecKillEventType;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SecKillRecoveryService<T> {

  private final SpringSecKillEventRepository repository;
  private final SecKillEventFormat eventFormat;

  public SecKillRecoveryService(SpringSecKillEventRepository repository,
      SecKillEventFormat eventFormat) {
    this.repository = repository;
    this.eventFormat = eventFormat;
  }

  public SecKillRecoveryCheckResult<T> check(PromotionEntity promotion) {
    List<EventEntity> entities = this.repository.findByPromotionId(promotion.getPromotionId());
    if (!entities.isEmpty()) {
      long count = entities.stream()
          .filter(event -> SecKillEventType.CouponGrabbedEvent.equals(event.getType()))
          .count();

      Set<T> claimedCustomers = ConcurrentHashMap.newKeySet();
      claimedCustomers.addAll(entities.stream()
          .filter(entity -> SecKillEventType.CouponGrabbedEvent.equals(entity.getType()))
          .map(entity -> ((CouponGrabbedEvent<T>) eventFormat.fromEntity(entity)).getCoupon().getCustomerId())
          .collect(Collectors.toSet()));
      boolean isFinished = entities.stream()
          .anyMatch(event -> SecKillEventType.PromotionFinishEvent.equals(event.getType()));
      long lastSeq = 0;
      for (EventEntity entity : entities) {
        lastSeq = Math.max(lastSeq, entity.getSeq());
      }
      if (lastSeq == 0) {
        lastSeq = entities.size();
      }
      return new SecKillRecoveryCheckResult<T>(true, isFinished,
          promotion.getNumberOfCoupons() - (int) count, claimedCustomers, lastSeq);
    }
    return new SecKillRecoveryCheckResult<T>(promotion.getNumberOfCoupons());
  }
}
