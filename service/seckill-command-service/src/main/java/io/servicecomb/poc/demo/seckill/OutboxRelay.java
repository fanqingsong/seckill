/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ OutboxRelay.java                                │
 * │ 链路：抢券 · Command 发消息                     │
 * └─────────────────────────────────────────────────┘
 *
 * 事件行和 outbox 已在同一事务提交
 * │
 * ▼
 * 【本文件】读未发布的 outbox，发到 Kafka seckill.events
 * │
 * └─ 成功后再把该行标成已发布
 *
 * 一句话：先提交数据库，再发 Kafka；HTTP 成功时这里可能还没跑到。
 */

package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import io.servicecomb.poc.demo.seckill.kafka.SecKillEventPublisher;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringOutboxRepository;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 事务已经提交之后，把 outbox 表里还没发出的行投递到 Kafka。
 * <p>
 * {@code @Component} 表示 Spring 启动时创建本类的一个实例。构造器参数是构造器注入：
 * Spring 按类型找到仓库和发布器并传进来，本类不自己 {@code new} 它们。
 * <p>
 * 活动开始、活动结束的 outbox 由本服务写入；抢券成功的 {@code CouponGrabbedEvent}
 * 由 Persist 服务写入同一张表。本类不参加 HTTP，也不扣 Redis。定时任务读
 * {@code published = false} 的行，发到 topic {@code seckill.events}（key 为活动编号），
 * 成功后再把该行标成已发布。
 * <p>
 * 这就是 outbox relay：先提交数据库，再发消息。抢券接口返回「已接受」时，PostgreSQL
 * 可能还没有事件行；即便 Persist 已经写入，本类也可能还没跑到那一行，查询页会再晚一点。
 */
@Component
public class OutboxRelay {

  private static final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);

  private final SpringOutboxRepository outboxRepository;
  private final SecKillEventPublisher publisher;

  /**
   * 保存依赖，并立刻开始轮询未发布的 outbox。
   * <p>
   * {@code scheduleWithFixedDelay} 的时间是：首次等待 200 毫秒，每轮跑完后再等 200 毫秒。
   * 单线程是为了避免两轮同时改同一批行。
   *
   * @param outboxRepository 读、改 PostgreSQL 的 outbox 表
   * @param publisher 把已提交的 payload 发到 Kafka，不在这里写事件表
   */
  public OutboxRelay(SpringOutboxRepository outboxRepository, SecKillEventPublisher publisher) {
    this.outboxRepository = outboxRepository;
    this.publisher = publisher;
    // outbox relay：只投递已经提交的行；本轮失败的行保持未发布，下一轮再发。
    Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(this::publishPending, 200, 200,
        TimeUnit.MILLISECONDS);
  }

  /**
   * 取出最多 50 条未发布记录，按 id 从早到晚逐条发到 Kafka，再标记已发布。
   * <p>
   * 没有返回值。某一行发布或保存失败时，本轮停下；已经 {@code save} 成功的行保持已发布，
   * 还没保存的行留在表里。本方法不改库存。
   */
  void publishPending() {
    try {
      List<OutboxEntity> batch = outboxRepository.findTop50ByPublishedFalseOrderByIdAsc();
      for (OutboxEntity row : batch) {
        publisher.publish(row.getKafkaKey(), row.getPayload());
        row.markPublished();
        outboxRepository.save(row);
      }
    } catch (RuntimeException e) {
      // 发 Kafka 或写回 outbox 失败：吞掉异常，避免定时线程退出。未标记的行下一轮继续。
      logger.warn("Outbox relay failed: {}", e.getMessage());
    }
  }
}
