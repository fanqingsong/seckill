package io.servicecomb.poc.demo.seckill.es;

import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SecKillSearchIndex} 的内存实现，供 {@code seckill.infra.mode} 不是 {@code prod} 时使用。
 * <p>
 * 它代替 Elasticsearch 的两个索引：活动放在 {@link #promotions}，券放在 {@link #coupons}。
 * 查询页的搜索调用 {@link #search}；列表和「我的券」仍读 Redis 读模型，不读这两个 Map。
 * 投影来自 Event 服务，不在抢券 HTTP 线程里发生。
 */
public class InMemorySearchIndex implements SecKillSearchIndex {

  /** 券文档。键是 {@code promotionId:customerId}，与 HTTP 实现里的文档 id 相同。 */
  private final Map<String, CouponEntity<String>> coupons = new ConcurrentHashMap<String, CouponEntity<String>>();
  /** 活动文档。键是活动编号。结束时从这里移除，券 Map 保留。 */
  private final Map<String, PromotionEntity> promotions = new ConcurrentHashMap<String, PromotionEntity>();

  /**
   * 按活动编号放入或覆盖活动。对应 HTTP 实现的 {@code PUT /seckill-promotions/_doc/{id}}。
   *
   * @param promotion 活动开始事件里的活动
   */
  @Override
  public void indexPromotion(PromotionEntity promotion) {
    promotions.put(promotion.getPromotionId(), promotion);
  }

  /**
   * 按 {@code promotionId:customerId} 放入或覆盖券。对应 {@code PUT /seckill-coupons/_doc/{id}}。
   *
   * @param coupon 要能被 {@link #search} 查到的券
   */
  @Override
  public void indexCoupon(CouponEntity<String> coupon) {
    coupons.put(coupon.getPromotionId() + ":" + coupon.getCustomerId(), coupon);
  }

  /**
   * 从活动 Map 删除该活动。已索引的券仍留在 {@link #coupons}，搜索还能命中。
   * <p>
   * 与 HTTP 实现不同：这里没有 {@code finished} 字段可更新，用移除活动文档表示结束。
   *
   * @param promotionId 活动编号
   */
  @Override
  public void markPromotionFinished(String promotionId) {
    promotions.remove(promotionId);
  }

  /**
   * 在内存券文档里按顾客和活动过滤。两个条件都为空时返回全部券。
   *
   * @param customerId 顾客编号。null 或空串表示不限制顾客
   * @param promotionId 活动编号。null 或空串表示不限制活动
   * @return 同时满足两个条件的券。没有顺序保证
   */
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
