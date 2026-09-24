/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ SecKillRecoveryService.java                     │
 * │ 链路：抢券 · Command 恢复                       │
 * └─────────────────────────────────────────────────┘
 *
 * publishTime，Redis 还没有库存键
 * │
 * ▼
 * 【本文件】只读事件表，算出剩余张数和已抢顾客
 * │
 * ▼
 * 引导器据此初始化 Redis（本文件不写库、不发 Kafka）
 *
 * 一句话：重启前先看事件表，避免把已卖出的券再放回 Redis。
 */

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

/**
 * 用 PostgreSQL 里已经追加的事件，算出活动重启时 Redis 应该恢复成什么样。
 * <p>
 * {@link SecKillPromotionBootstrap} 在初始化库存前调用 {@link #check}。本类只读事件表，
 * 不写 Redis，不发 Kafka。没有事件时，剩余张数等于活动上的券总数，表示这一活动还没开始过。
 * <p>
 * 类名后面的 {@code <T>} 是顾客编号的泛型。从抢券事件里取出的顾客编号会放进结果集，类型视为 T。
 */
public class SecKillRecoveryService<T> {

  private final SpringSecKillEventRepository repository;
  private final SecKillEventFormat eventFormat;

  /**
   * @param repository 按活动编号查出全部已追加事件
   * @param eventFormat 把抢券事件行还原成对象，以便读取顾客编号
   */
  public SecKillRecoveryService(SpringSecKillEventRepository repository,
      SecKillEventFormat eventFormat) {
    this.repository = repository;
    this.eventFormat = eventFormat;
  }

  /**
   * 扫描一个活动的全部事件，整理成恢复快照。
   *
   * @param promotion 活动行。用它的编号查事件，用券总数减去抢券条数得到剩余库存
   * @return 是否已有事件、是否已结束、剩余张数、已抢顾客和最大序号。没有事件时剩余张数等于券总数
   */
  public SecKillRecoveryCheckResult<T> check(PromotionEntity promotion) {
    List<EventEntity> entities = this.repository.findByPromotionId(promotion.getPromotionId());
    if (!entities.isEmpty()) {
      // 事件表非空：按抢券事件恢复已抢顾客。剩余张数 = 券总数 - 抢券事件条数。
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
        // 旧数据没有序号时，用事件条数当作下一次序号的起点，避免新事件和旧行撞号。
        lastSeq = entities.size();
      }
      // 第一个参数固定为 true：只要有事件就视为已经开始，引导器不会再补写 PromotionStartEvent。
      return new SecKillRecoveryCheckResult<T>(true, isFinished,
          promotion.getNumberOfCoupons() - (int) count, claimedCustomers, lastSeq);
    }
    // 还没有任何事件：活动尚未开始，剩余张数就是券总数。
    return new SecKillRecoveryCheckResult<T>(promotion.getNumberOfCoupons());
  }
}
