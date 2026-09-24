/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ SecKillQueryService.java                        │
 * │ 链路：查询 · 读服务                             │
 * └─────────────────────────────────────────────────┘
 *
 * 控制器把 HTTP 转进来
 * │
 * ▼
 * 【本文件】按接口选择存储
 * │
 * ├── 我的券 / 进行中活动 / 增量券 ──▶ Redis
 * └── 搜索 ──▶ Elasticsearch
 *
 * 一句话：不写库存，也不扫事件表；刚抢到的券可能还查不到。
 */

package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.es.SecKillSearchIndex;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import java.util.Collection;

/**
 * 查询链路上的读服务。列表和「我的券」读 Redis 读模型，搜索才读 Elasticsearch。
 * <p>
 * 控制器把 HTTP 转给本类。本类不写库存，也不扫 PostgreSQL 事件表。读模型由 Event 服务消费 Kafka
 * 之后投影出来。抢券 HTTP 刚返回成功时，这里可能还查不到那张券，要等投影完成。
 */
public class SecKillQueryService {

  private final SecKillStore store;
  private final SecKillSearchIndex searchIndex;

  /**
   * @param store Redis 读模型：进行中的活动、顾客的券、按编号递增的券列表
   * @param searchIndex Elasticsearch 索引，只给搜索方法用
   */
  public SecKillQueryService(SecKillStore store, SecKillSearchIndex searchIndex) {
    this.store = store;
    this.searchIndex = searchIndex;
  }

  /**
   * 查出一名顾客已经投影到 Redis 的券。
   *
   * @param customerId 顾客编号
   * @return 读模型里已有的券；刚抢成功但尚未投影的券不在其中
   */
  public Collection<CouponEntity<String>> getCustomerCoupons(String customerId) {
    return store.customerCoupons(customerId);
  }

  /**
   * 列出 Redis 里仍标记为进行中的活动。
   *
   * @return 读模型中的活动；卖完或结束后，Event 服务会把它们从这里移走
   */
  public Collection<PromotionEntity> getActivePromotions() {
    return store.activePromotions();
  }

  /**
   * 取出编号大于 {@code latestId} 的券，供页面增量同步。
   *
   * @param latestId 客户端已经见过的最大券编号
   * @return Redis 读模型里更新的券，不查 Elasticsearch
   */
  public Collection<CouponEntity<String>> getLatestCoupons(int latestId) {
    return store.couponsAfter(latestId);
  }

  /**
   * 按顾客或活动搜索券。这是查询服务里唯一读 Elasticsearch 的方法。
   *
   * @param customerId 可空，空则不按顾客过滤
   * @param promotionId 可空，空则不按活动过滤
   * @return 索引里匹配的券；索引同样晚于抢券 HTTP 成功
   */
  public Collection<CouponEntity<String>> search(String customerId, String promotionId) {
    return searchIndex.search(customerId, promotionId);
  }
}
