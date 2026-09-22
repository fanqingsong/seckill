package io.servicecomb.poc.demo.seckill.es;

import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.Collections;
import java.util.List;

public interface SecKillSearchIndex {

  void indexPromotion(PromotionEntity promotion);

  void indexCoupon(CouponEntity<String> coupon);

  void markPromotionFinished(String promotionId);

  List<CouponEntity<String>> search(String customerId, String promotionId);
}
