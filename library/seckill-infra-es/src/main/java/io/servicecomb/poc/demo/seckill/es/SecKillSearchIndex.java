package io.servicecomb.poc.demo.seckill.es;

import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.Collections;
import java.util.List;

/**
 * Elasticsearch 上的秒杀搜索索引。查询页的搜索走这里；活动列表和「我的券」不走这里，它们读 Redis 读模型。
 * <p>
 * Event 服务投影 Kafka {@code seckill.events} 时写入：活动开始调用 {@link #indexPromotion}，
 * 抢券成功调用 {@link #indexCoupon}，活动结束调用 {@link #markPromotionFinished}。
 * {@code seckill.infra.mode=prod} 时实现是 {@link HttpElasticsearchIndex}，
 * 其它值（缺省 {@code memory}）是 {@link InMemorySearchIndex}。本接口不扣库存，也不发 Kafka。
 */
public interface SecKillSearchIndex {

  /**
   * 把活动放进搜索索引，供按活动检索时对照。
   * <p>
   * 文档 id 使用活动编号。重复投影会覆盖同一条文档。
   *
   * @param promotion 活动开始事件里的活动
   */
  void indexPromotion(PromotionEntity promotion);

  /**
   * 把一张券放进搜索索引。查询页 {@code search} 读的就是这些文档。
   * <p>
   * 文档 id 是 {@code promotionId:customerId}。同一顾客在同一活动里再次投影会覆盖。
   *
   * @param coupon 已经写入 Redis 读模型的券。搜索结果返回的是索引里的这份
   */
  void indexCoupon(CouponEntity<String> coupon);

  /**
   * 把活动标成已结束。
   * <p>
   * HTTP 实现只更新活动文档上的 {@code finished} 字段。内存实现从活动 Map 里移除。
   * 两种实现都保留已经索引的券，搜索仍能查到这张券。
   *
   * @param promotionId 结束的活动编号
   */
  void markPromotionFinished(String promotionId);

  /**
   * 按顾客、活动检索券。这是查询页的搜索入口，不读 Redis 列表。
   *
   * @param customerId 顾客编号。null 或空串表示不按顾客过滤
   * @param promotionId 活动编号。null 或空串表示不按活动过滤。两个都空时，HTTP 实现会匹配全部券文档
   * @return 命中的券。索引或索引库还不存在时，HTTP 实现返回空列表
   */
  List<CouponEntity<String>> search(String customerId, String promotionId);
}
