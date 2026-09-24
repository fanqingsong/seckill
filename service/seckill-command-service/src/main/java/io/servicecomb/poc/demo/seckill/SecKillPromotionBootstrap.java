package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.PromotionStartEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 到 {@code publishTime} 才把活动装进 Redis，并在结束时间已到且抢券队列排空后写出结束事件。
 * <p>
 * {@link SecKillCommandConfig} 创建本类后立刻调用 {@link #run()}。本类不接收 HTTP。
 * 活动行由 Admin 事先写入 PostgreSQL；这里只是读出来。时间未到不初始化库存。
 * 时间到了且 Redis 还没有库存键时，按事件表恢复；若事件表也是空的，再写入
 * {@code PromotionStartEvent} 和 outbox。Kafka 不在这里发，交给 {@link OutboxRelay}。
 * <p>
 * 类名后面的 {@code <T>} 是顾客编号的泛型。配置类里实际传入的是 {@code String}。
 */
public class SecKillPromotionBootstrap<T> {

  private static final Logger logger = LoggerFactory.getLogger(SecKillPromotionBootstrap.class);

  private final SpringPromotionRepository promotionRepository;
  private final Map<String, SecKillCommandService<T>> commandServices;
  private final SecKillRecoveryService<T> recoveryService;
  private final SecKillStore store;
  private final TransactionalEventOutboxWriter writer;
  private final SecKillEventFormat eventFormat;

  /** 已经看到、但 publishTime 还没到的活动。键是 promotionId。 */
  private final Map<String, PromotionEntity> waitingPromotions = new HashMap<String, PromotionEntity>();
  /** 活动表里已经扫过的最大主键。下一轮只查 id 更大的新行。 */
  private int loadedPromotionId = 0;

  /**
   * 接住配置类传进来的依赖。本构造器不启动线程，线程在 {@link #run()} 里启动。
   *
   * @param promotionRepository 读活动表
   * @param commandServices 到点后放入 {@link SecKillCommandService} 的那份 Map
   * @param recoveryService 用事件表计算剩余库存
   * @param store Redis 库存；没有键时才 {@code initStock}
   * @param writer 写入 PromotionStartEvent 和 outbox，不发 Kafka
   * @param eventFormat 把开始事件转成可落库的消息
   */
  public SecKillPromotionBootstrap(
      SpringPromotionRepository promotionRepository,
      Map<String, SecKillCommandService<T>> commandServices,
      SecKillRecoveryService<T> recoveryService,
      SecKillStore store,
      TransactionalEventOutboxWriter writer,
      SecKillEventFormat eventFormat) {
    this.promotionRepository = promotionRepository;
    this.commandServices = commandServices;
    this.recoveryService = recoveryService;
    this.store = store;
    this.writer = writer;
    this.eventFormat = eventFormat;
  }

  /**
   * 启动单线程定时任务：马上跑第一轮，之后每轮结束再隔 500 毫秒。
   * 不在本方法里改库存。
   */
  public void run() {
    Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(this::tick, 0, 500, TimeUnit.MILLISECONDS);
  }

  /**
   * 一轮调度：装载新活动，启动到点的等待活动，再尝试结束已到期且队列已空的活动。
   * 不直接发 Kafka。
   */
  private void tick() {
    Iterable<PromotionEntity> promotions = promotionRepository.findByIdGreaterThan(loadedPromotionId);
    for (PromotionEntity promotion : promotions) {
      if (promotion.getPublishTime().getTime() <= System.currentTimeMillis()) {
        // 已到 publishTime：初始化 Redis，并在事件表还空着时写入 PromotionStartEvent。
        startUpPromotion(promotion);
        logger.info("PromotionEntity started = {}", promotion);
      } else {
        // 活动尚未开始：先放进等待表，这一轮不写 Redis 库存。
        waitingPromotions.put(promotion.getPromotionId(), promotion);
      }
      loadedPromotionId = promotion.getId();
    }

    for (String promotionId : new HashSet<String>(waitingPromotions.keySet())) {
      PromotionEntity promotion = waitingPromotions.get(promotionId);
      if (promotion.getPublishTime().getTime() <= System.currentTimeMillis()) {
        // 原先未开始的活动到点了，从等待表移除并启动。
        startUpPromotion(promotion);
        logger.info("PromotionEntity started = {}", promotion);
        waitingPromotions.remove(promotionId);
      }
    }

    for (SecKillCommandService<T> service : commandServices.values()) {
      // 单次抢券是否已经结束，由 addCouponTo 自己拒绝。结束事件要等抢券队列空了再写，见 finishExpired。
      // finishTime handled inside addCouponTo; still emit finish when time reached
    }
    finishExpired();
  }

  /**
   * 墙上时钟已过 {@code finishTime}，并且 Redis 抢券流里没有待处理令牌时，写结束事件。
   * <p>
   * {@code pendingGrabCount() == 0} 表示抢券队列已空：消费者组里既没有未投递的消息，
   * 也没有尚未 ack 的消息。队列不空就先跳过，避免 Persist 还在写抢券事件时先写下结束事件。
   */
  private void finishExpired() {
    for (PromotionEntity promotion : promotionRepository.findAll()) {
      SecKillCommandService<T> service = commandServices.get(promotion.getPromotionId());
      if (service != null && promotion.getFinishTime().getTime() <= System.currentTimeMillis()
          && store.pendingGrabCount() == 0) {
        // 已过结束时间，且抢券队列已空：可以追加 PromotionFinishEvent。
        service.finish();
      }
    }
  }

  /**
   * 为一个活动创建内存中的命令服务。Redis 还没有库存键时，按事件表恢复或从零初始化。
   *
   * @param promotion Admin 写入的活动行，含券数量、publishTime 和 finishTime
   */
  private void startUpPromotion(PromotionEntity promotion) {
    if (commandServices.containsKey(promotion.getPromotionId())) {
      // 本进程已经创建过该活动的命令服务，避免把 Redis 库存再初始化一遍。
      return;
    }
    SecKillRecoveryCheckResult<T> recoveryInfo = recoveryService.check(promotion);
    if (!store.stockKeysPresent(promotion.getPromotionId())) {
      // Redis 里还没有这批库存（首次到点，或 Redis 被清空）：用事件表里的剩余张数和已抢顾客重建。
      Set<String> claimed = new HashSet<String>();
      for (T customer : recoveryInfo.getClaimedCustomers()) {
        claimed.add(String.valueOf(customer));
      }
      store.initStock(promotion.getPromotionId(), recoveryInfo.remainingCoupons(), claimed, recoveryInfo.lastSeq());
      if (!recoveryInfo.isStarted()) {
        // 事件表还是空的：补一条 PromotionStartEvent。只提交 PostgreSQL 和 outbox，不在这里发 Kafka。
        long seq = store.nextSeq(promotion.getPromotionId());
        EventMessageDto start = eventFormat.toMessage(new PromotionStartEvent(promotion), UUID.randomUUID().toString(),
            seq);
        writer.persist(start);
      }
    }
    SecKillCommandService<T> service = new SecKillCommandService<T>(promotion, store, writer, eventFormat,
        recoveryInfo.isFinished());
    commandServices.put(promotion.getPromotionId(), service);
  }
}
