package io.servicecomb.poc.demo.seckill.persist;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionFinishEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.redis.GrabToken;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 消费 Redis 抢券流，把每次成功扣减落成 PostgreSQL 事件。
 * <p>
 * Command 的 HTTP 线程只做 Redis Lua。Lua 成功后令牌留在 Redis Stream 里。
 * {@code @Component} 表示 Spring 启动时创建本对象。构造器参数是构造器注入，并且构造器末尾就启动后台线程，
 * 不等到第一次 HTTP 请求。本类没有对外的抢券接口。
 * <p>
 * 每一条令牌经 {@link PersistOutboxWriter#persist} 写入：{@code CouponGrabbedEvent} 和 outbox
 * 在同一个事务里提交。这就是「HTTP 已经成功，但 PostgreSQL 可能还没有这一行」的后半段。
 * 卖完最后一张时，本类再追加 {@code PromotionFinishEvent}。Kafka 不在这里发。
 */
@Component
public class GrabPersistWorker {

  private static final Logger logger = LoggerFactory.getLogger(GrabPersistWorker.class);
  /** 抢券流这一轮没有消息时，阻塞等待的最长时间。到点返回 null，循环继续。 */
  private static final long POLL_TIMEOUT_MS = 1000;
  /** 活动行还没出现，或落库抛出意外异常时，稍等再试，避免空转打满数据库。 */
  private static final long RETRY_BACKOFF_MS = 50;

  private final SecKillStore store;
  private final SpringPromotionRepository promotionRepository;
  private final PersistOutboxWriter writer;
  private final SecKillEventFormat eventFormat;
  /** 为 false 时循环退出。容器关闭时由 {@link #stop()} 改掉。 */
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ExecutorService executor;

  /**
   * 保存依赖，并启动名为 {@code grab-persist} 的守护线程去拉流。
   * <p>
   * 守护线程的意思是：主进程退出时它不会单独把 JVM 留住。{@code executor.execute} 立刻调用 {@link #loop}。
   *
   * @param store Redis 抢券流。{@code pollInflight} 取出尚未 ack 的令牌
   * @param promotionRepository 按活动编号读取 Admin 写入的活动行，用来拼事件
   * @param writer 同一事务写入事件和 outbox
   * @param eventFormat 把抢券事件、结束事件转成消息
   */
  public GrabPersistWorker(SecKillStore store, SpringPromotionRepository promotionRepository,
      PersistOutboxWriter writer, SecKillEventFormat eventFormat) {
    this.store = store;
    this.promotionRepository = promotionRepository;
    this.writer = writer;
    this.eventFormat = eventFormat;
    this.executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "grab-persist");
      thread.setDaemon(true);
      return thread;
    });
    executor.execute(this::loop);
  }

  /**
   * Spring 容器关闭时调用。{@code @PreDestroy} 表示这个方法在 Bean 销毁前执行。
   * 先让循环看见 {@code running = false}，再中断正在阻塞读取 Redis 的线程。
   */
  @PreDestroy
  public void stop() {
    running.set(false);
    executor.shutdownNow();
  }

  /**
   * 一直拉取抢券流，直到 {@link #stop()} 或线程被中断。
   * 单次失败只打日志并稍等，不让线程退出，否则之后的令牌再也无人落库。
   */
  void loop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        drainOne();
      } catch (RuntimeException e) {
        logger.warn("Grab persist worker failed: {}", e.getMessage());
        sleepBackoff();
      }
    }
  }

  /**
   * 处理抢券流里的一条令牌。
   *
   * @return 本条已经写入数据库，或确认是重复顾客从而 ack 掉时为 true。没取到令牌、活动行还不存在、或需要重试时为 false
   */
  boolean drainOne() {
    GrabToken token = store.pollInflight(POLL_TIMEOUT_MS);
    if (token == null) {
      // 抢券队列这一轮是空的：没有未投递的新令牌，也没有待重试的旧令牌。
      return false;
    }
    PromotionEntity promotion = promotionRepository.findTopByPromotionId(token.getPromotionId());
    if (promotion == null) {
      // 活动尚未写入 PostgreSQL（或编号对不上）。令牌放回流里，稍后再读，避免丢掉一次已经扣减的库存。
      store.deferInflight(token);
      sleepBackoff();
      return false;
    }
    try {
      writer.persist(grabbedMessage(promotion, token));
      store.ackGrab(token);
      finishIfLast(promotion, token);
      return true;
    } catch (DataIntegrityViolationException duplicate) {
      // 重复顾客：事件表的唯一约束说明这张券已经落库。当成成功并 ack，避免同一条令牌无限重试。
      store.ackGrab(token);
      finishIfLast(promotion, token);
      return true;
    } catch (RuntimeException e) {
      // 数据库暂时失败：不 ack。令牌仍留在 Redis，下一轮还能读到。
      logger.warn("Persist grab failed, will retry. customer={}", token.getCustomerId(), e);
      sleepBackoff();
      return false;
    }
  }

  /**
   * 用活动行和令牌拼出一条待落库的抢券消息。不访问数据库。
   *
   * @param promotion 活动表里的那一行，提供活动编号和券信息
   * @param token Redis 里的令牌。序号沿用 Lua 分配的 seq，保证和库存扣减顺序一致
   * @return 交给 {@link PersistOutboxWriter#persist} 的消息
   */
  private EventMessageDto grabbedMessage(PromotionEntity promotion, GrabToken token) {
    CouponGrabbedEvent<String> event = new CouponGrabbedEvent<String>(promotion, token.getCustomerId());
    return eventFormat.toMessage(event, UUID.randomUUID().toString(), token.getSeq());
  }

  /**
   * 若这一张是最后的库存，并且事件表里还没有结束事件，就追加 {@code PromotionFinishEvent}。
   * <p>
   * 按时间结束、且要等抢券队列排空的那条路径在 Command 的引导器里，不在本方法。
   *
   * @param promotion 当前活动
   * @param token 刚刚落库的令牌。{@code remaining} 是扣减之后还剩的张数
   */
  private void finishIfLast(PromotionEntity promotion, GrabToken token) {
    if (token.getRemaining() > 0 || writer.hasFinishEvent(promotion.getPromotionId())) {
      // 还没卖完，或结束事件已经有了。卖完的判断是剩余张数为 0，不是去数队列长度。
      return;
    }
    try {
      PromotionFinishEvent event = new PromotionFinishEvent(promotion);
      long seq = store.nextSeq(promotion.getPromotionId());
      writer.persist(eventFormat.toMessage(event, UUID.randomUUID().toString(), seq));
    } catch (DataIntegrityViolationException duplicate) {
      // 另一个写入方已经存过结束事件，唯一约束拒绝了第二条。库存状态不用改。
      logger.info("Finish event already stored for {}", promotion.getPromotionId());
    } catch (RuntimeException e) {
      logger.warn("Failed to persist finish event {}", promotion.getPromotionId(), e);
    }
  }

  /**
   * 暂停 {@link #RETRY_BACKOFF_MS} 毫秒。若等待时被中断，把中断标记设回去，让 {@link #loop} 能退出。
   */
  private void sleepBackoff() {
    try {
      Thread.sleep(RETRY_BACKOFF_MS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
