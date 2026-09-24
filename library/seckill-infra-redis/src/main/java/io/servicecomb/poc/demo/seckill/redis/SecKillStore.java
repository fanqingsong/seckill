package io.servicecomb.poc.demo.seckill.redis;

import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface SecKillStore {

  void initStock(String promotionId, int remaining, Set<String> claimedCustomers, long lastSeq);

  GrabAttempt tryGrab(String promotionId, String customerId);

  void compensateGrab(String promotionId, String customerId);

  GrabToken pollInflight(long timeoutMillis);

  void ackGrab(GrabToken token);

  void deferInflight(GrabToken token);

  int pendingGrabCount();

  boolean stockKeysPresent(String promotionId);

  long nextSeq(String promotionId);

  Collection<PromotionEntity> activePromotions();

  Collection<CouponEntity<String>> customerCoupons(String customerId);

  Collection<CouponEntity<String>> couponsAfter(int latestId);

  void saveActivePromotion(PromotionEntity promotion);

  void removeActivePromotion(String promotionId);

  CouponEntity<String> saveCoupon(CouponEntity<String> coupon);

  long appliedSeq(String promotionId);

  void setAppliedSeq(String promotionId, long seq);

  void buffer(io.servicecomb.poc.demo.seckill.dto.EventMessageDto event);

  List<io.servicecomb.poc.demo.seckill.dto.EventMessageDto> drainBuffer(String promotionId);

  List<io.servicecomb.poc.demo.seckill.dto.EventMessageDto> searchCoupons(String customerId, String promotionId);
}
