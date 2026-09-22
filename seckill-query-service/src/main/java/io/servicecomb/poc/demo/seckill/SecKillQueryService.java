package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.es.SecKillSearchIndex;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import java.util.Collection;

public class SecKillQueryService {

  private final SecKillStore store;
  private final SecKillSearchIndex searchIndex;

  public SecKillQueryService(SecKillStore store, SecKillSearchIndex searchIndex) {
    this.store = store;
    this.searchIndex = searchIndex;
  }

  public Collection<CouponEntity<String>> getCustomerCoupons(String customerId) {
    return store.customerCoupons(customerId);
  }

  public Collection<PromotionEntity> getActivePromotions() {
    return store.activePromotions();
  }

  public Collection<CouponEntity<String>> getLatestCoupons(int latestId) {
    return store.couponsAfter(latestId);
  }

  public Collection<CouponEntity<String>> search(String customerId, String promotionId) {
    return searchIndex.search(customerId, promotionId);
  }
}
