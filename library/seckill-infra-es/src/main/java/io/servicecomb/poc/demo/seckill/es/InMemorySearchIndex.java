package io.servicecomb.poc.demo.seckill.es;

import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySearchIndex implements SecKillSearchIndex {

  private final Map<String, CouponEntity<String>> coupons = new ConcurrentHashMap<String, CouponEntity<String>>();
  private final Map<String, PromotionEntity> promotions = new ConcurrentHashMap<String, PromotionEntity>();

  @Override
  public void indexPromotion(PromotionEntity promotion) {
    promotions.put(promotion.getPromotionId(), promotion);
  }

  @Override
  public void indexCoupon(CouponEntity<String> coupon) {
    coupons.put(coupon.getPromotionId() + ":" + coupon.getCustomerId(), coupon);
  }

  @Override
  public void markPromotionFinished(String promotionId) {
    promotions.remove(promotionId);
  }

  @Override
  public List<CouponEntity<String>> search(String customerId, String promotionId) {
    List<CouponEntity<String>> result = new ArrayList<CouponEntity<String>>();
    for (CouponEntity<String> coupon : coupons.values()) {
      boolean customerOk = customerId == null || customerId.isEmpty() || customerId.equals(coupon.getCustomerId());
      boolean promotionOk =
          promotionId == null || promotionId.isEmpty() || promotionId.equals(coupon.getPromotionId());
      if (customerOk && promotionOk) {
        result.add(coupon);
      }
    }
    return result;
  }
}
