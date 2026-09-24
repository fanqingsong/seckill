package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionFinishEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.redis.GrabAttempt;
import io.servicecomb.poc.demo.seckill.redis.GrabToken;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个活动在抢券热路径上的写服务。
 * <p>
 * 控制器收到 {@code POST /command/coupons/} 后调用 {@link #addCouponTo}。判断和扣减只发生在
 * Redis Lua（{@link SecKillStore#tryGrab}）：库存、已抢顾客、写入抢券流，一次完成。
 * 成功返回时不写 PostgreSQL，也不发 Kafka。Persist 服务稍后消费这条流，再在一个事务里写事件和 outbox。
 * <p>
 * 所以 HTTP 返回「已接受」只表示 Redis 已扣减。PostgreSQL 里可能还没有这张券，查询页晚一点才能看见。
 * 这是预期行为，不是漏写。{@code PromotionStartEvent} 不在本类写入，而在
 * {@link SecKillPromotionBootstrap} 于 {@code publishTime} 初始化 Redis 时写入。
 * <p>
 * 类名后面的 {@code <T>} 是泛型：顾客编号的具体类型由调用方决定，本类不关心它是字符串还是别的类型。
 */
public class SecKillCommandService<T> {

  private static final Logger logger = LoggerFactory.getLogger(SecKillCommandService.class);

  private final PromotionEntity promotion;
  private final SecKillStore store;
  private final TransactionalEventOutboxWriter writer;
  private final SecKillEventFormat eventFormat;
  /** 本进程是否已经认为活动结束。为 true 时抢券直接失败，不再访问 Redis。 */
  private final AtomicBoolean finished = new AtomicBoolean();

  /**
   * @param promotion 活动行，含结束时间和活动编号
   * @param store Redis 库存与抢券流
   * @param writer 结束事件的落库。抢券成功路径不调用它
   * @param eventFormat 把结束事件转成消息
   * @param alreadyFinished 事件表里已经有结束事件时为 true，构造后就拒绝新的抢券
   */
  public SecKillCommandService(PromotionEntity promotion, SecKillStore store,
      TransactionalEventOutboxWriter writer, SecKillEventFormat eventFormat, boolean alreadyFinished) {
    this.promotion = promotion;
    this.store = store;
    this.writer = writer;
    this.eventFormat = eventFormat;
    this.finished.set(alreadyFinished);
  }

  /** @return 引导器传进来的活动行 */
  public PromotionEntity getPromotion() {
    return promotion;
  }

  /**
   * 为一名顾客抢一张券。
   *
   * @param customerId 顾客编号。同一活动内重复提交应得到「已经抢过」，而不是再扣库存
   * @return 成功只表示 Redis 已扣减。失败原因见 {@link SecKillGrabResult}。这里不写 PostgreSQL
   */
  public SecKillGrabResult addCouponTo(T customerId) {
    if (finished.get() || promotion.getFinishTime().getTime() < System.currentTimeMillis()) {
      // 活动已经结束：内存标记为真，或墙上时钟已经超过 finishTime。直接拒绝，不再访问 Redis。
      return SecKillGrabResult.Failed;
    }
    String customer = String.valueOf(customerId);
    GrabAttempt attempt = store.tryGrab(promotion.getPromotionId(), customer);
    if (attempt.isDuplicate()) {
      // 重复顾客：此人已在 Redis 已抢集合里，Lua 没有再次扣库存。
      return SecKillGrabResult.Duplicate;
    }
    if (!attempt.isSuccess()) {
      // 活动尚未开始（Redis 还没有库存键）或已经卖完。两种都映射成失败，不写 PostgreSQL。
      return SecKillGrabResult.Failed;
    }
    // Lua 已扣减，令牌在 Redis 抢券流里。HTTP 可以返回成功，但 PostgreSQL 还没有这一行。
    return SecKillGrabResult.Success;
  }

  /**
   * 把一条抢券令牌写成 {@code CouponGrabbedEvent}，并和 outbox 放进同一个事务。
   * <p>
   * 当前 HTTP 入口不调用本方法，因此抢券成功不会在 Command 进程里同步落库。
   * 线上消费 Redis 抢券流并写入同类事件的是 Persist 服务。本方法保留给需要在本进程内直接落库的调用。
   *
   * @param token Redis 抢券流里的令牌，含顾客、序号和扣减后的剩余张数
   */
  @SuppressWarnings("unchecked")
  public void persistGrab(GrabToken token) {
    // GrabToken 里的顾客编号是字符串。调用方约定 T 就是这个编号的类型，因此做一次强制转换。
    CouponGrabbedEvent<T> event = new CouponGrabbedEvent<T>(promotion, (T) token.getCustomerId());
    EventMessageDto message = eventFormat.toMessage(event, UUID.randomUUID().toString(), token.getSeq());
    writer.persist(message);
  }

  /**
   * 追加一条 {@code PromotionFinishEvent}。已经写过则直接返回。
   * <p>
   * 由引导器在结束时间已到、且抢券队列已空时调用。成功只提交 PostgreSQL 和 outbox，不发 Kafka。
   * 写入失败会把内存中的结束标记改回 false，下一轮可以再试。不回滚 Redis 库存。
   */
  public void finish() {
    if (finished.get() || writer.hasFinishEvent(promotion.getPromotionId())) {
      // 活动已经结束：内存标记为真，或事件表里已有结束事件。不再追加。
      finished.set(true);
      return;
    }
    if (!finished.compareAndSet(false, true)) {
      // 另一个线程刚刚抢先标记结束，本次放弃，避免写出两条结束事件。
      return;
    }
    try {
      PromotionFinishEvent event = new PromotionFinishEvent(promotion);
      long seq = store.nextSeq(promotion.getPromotionId());
      writer.persist(eventFormat.toMessage(event, UUID.randomUUID().toString(), seq));
    } catch (RuntimeException e) {
      logger.warn("Failed to persist finish event {}", promotion.getPromotionId(), e);
      // 事务失败：撤销内存标记，让后续调度再试。
      finished.set(false);
    }
  }
}
