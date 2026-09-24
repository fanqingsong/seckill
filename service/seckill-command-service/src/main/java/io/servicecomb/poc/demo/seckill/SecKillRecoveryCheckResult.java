package io.servicecomb.poc.demo.seckill;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 PostgreSQL 事件表扫出来的一份恢复快照，用来在 Redis 丢失或进程重启后重建库存。
 * <p>
 * {@link SecKillRecoveryService#check} 创建本对象，{@link SecKillPromotionBootstrap} 读取它。
 * 数据来自事件表，本类不写 Redis，也不发 Kafka。
 * <p>
 * 类名后面的 {@code <T>} 是泛型：顾客编号的具体类型由调用方决定，本类只负责装进集合。
 */
public class SecKillRecoveryCheckResult<T> {
  /** 调用方是否认为活动已经开始。事件表非空时为 true，并不单独查找 PromotionStartEvent。 */
  private final boolean startEventAvailable;
  /** 事件表里是否已经有 PromotionFinishEvent。 */
  private final boolean finishEventAvailable;
  /** 还应放回 Redis 的剩余张数。有事件时等于券总数减去抢券事件条数。 */
  private final int remainingCoupons;
  /** 已经抢到券的顾客。写回 Redis 的已抢集合，避免同一人再扣一次库存。 */
  private final Set<T> claimedCustomers;
  /** 已落库事件中的最大序号。Redis 之后的序号从这里接着编。 */
  private final long lastSeq;

  /**
   * 事件表里是否已经有本活动的记录。
   *
   * @return 有事件时为 true，引导器就不再补写 PromotionStartEvent
   */
  public boolean isStarted() {
    return startEventAvailable;
  }

  /** @return 恢复 Redis 时应写入的剩余张数 */
  public int remainingCoupons() {
    return remainingCoupons;
  }

  /** @return 事件表里是否已有结束事件 */
  public boolean isFinished() {
    return finishEventAvailable;
  }

  /** @return 已经抢到券的顾客，可能为空集合，不会是 null */
  public Set<T> getClaimedCustomers() {
    return claimedCustomers;
  }

  /** @return 已落库事件的最大序号；没有序号时由恢复逻辑改成事件条数 */
  public long lastSeq() {
    return lastSeq;
  }

  /**
   * 事件表还没有任何记录时使用：活动视为未开始、未结束，剩余张数由调用方传入。
   *
   * @param remainingCoupons 通常就是活动上的券总数
   */
  public SecKillRecoveryCheckResult(int remainingCoupons) {
    startEventAvailable = false;
    finishEventAvailable = false;
    this.remainingCoupons = remainingCoupons;
    this.claimedCustomers = ConcurrentHashMap.newKeySet();
    this.lastSeq = 0;
  }

  /**
   * 已有事件、但调用方不关心序号时使用。序号按 0 传入完整构造器。
   *
   * @param startEventAvailable 是否视为已经开始
   * @param finishEventAvailable 是否已经结束
   * @param remainingCoupons 还应剩余的张数
   * @param claimedCustomers 已抢顾客，由调用方保证非 null
   */
  public SecKillRecoveryCheckResult(boolean startEventAvailable, boolean finishEventAvailable, int remainingCoupons,
      Set<T> claimedCustomers) {
    this(startEventAvailable, finishEventAvailable, remainingCoupons, claimedCustomers, 0);
  }

  /**
   * 保存恢复扫描的全部结果。
   *
   * @param startEventAvailable 是否视为已经开始
   * @param finishEventAvailable 是否已经结束
   * @param remainingCoupons 还应剩余的张数
   * @param claimedCustomers 已抢顾客
   * @param lastSeq 已落库事件的最大序号
   */
  public SecKillRecoveryCheckResult(boolean startEventAvailable, boolean finishEventAvailable, int remainingCoupons,
      Set<T> claimedCustomers, long lastSeq) {
    this.startEventAvailable = startEventAvailable;
    this.finishEventAvailable = finishEventAvailable;
    this.remainingCoupons = remainingCoupons;
    this.claimedCustomers = claimedCustomers;
    this.lastSeq = lastSeq;
  }
}
