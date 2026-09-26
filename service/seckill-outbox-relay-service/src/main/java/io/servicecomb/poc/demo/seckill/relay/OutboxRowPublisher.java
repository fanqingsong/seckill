/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ OutboxRowPublisher.java                         │
 * │ 链路：outbox · 单行投递                         │
 * └─────────────────────────────────────────────────┘
 *
 * outbox 行已提交且 published=false
 * │
 * ▼
 * 【本文件】发 Kafka，再标 published
 * │
 * └─ PostgreSQL outbox 表
 *
 * 一句话：CDC 与轮询共用同一套单行逻辑，避免重复发。
 */

package io.servicecomb.poc.demo.seckill.relay;

import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import io.servicecomb.poc.demo.seckill.kafka.SecKillEventPublisher;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringOutboxRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把一行或一批 outbox 发到 Kafka，并在成功后写回 {@code published=true}。
 * <p>
 * {@code @Component} 由 Spring 创建单例。CDC 触发的按 id 投递与定时轮询的批量扫描都调用本类，
 * 保证「先 Kafka 再改行」的顺序一致。本类不读 Redis，也不写事件表。
 */
@Component
public class OutboxRowPublisher {

  private final SpringOutboxRepository outboxRepository;
  private final SecKillEventPublisher publisher;

  /**
   * 构造器注入：Spring 按类型传入仓库和发布器。
   *
   * @param outboxRepository 读写 PostgreSQL 的 outbox 表
   * @param publisher 发到 topic {@code seckill.events}，key 为活动编号
   */
  public OutboxRowPublisher(SpringOutboxRepository outboxRepository, SecKillEventPublisher publisher) {
    this.outboxRepository = outboxRepository;
    this.publisher = publisher;
  }

  /**
   * 扫描最早 50 条未发布行并逐条投递。轮询兜底路径调用本方法。
   * <p>
   * 某一行失败时抛出运行时异常，由调用方吞掉并留待下一轮；本方法之前已成功 {@code save} 的行保持已发布。
   */
  @Transactional
  public void publishPendingBatch() {
    List<OutboxEntity> batch = outboxRepository.findTop50ByPublishedFalseOrderByIdAsc();
    for (OutboxEntity row : batch) {
      publishOne(row);
    }
  }

  /**
   * 按主键投递一行。CDC {@code NOTIFY} 携带 outbox id 时调用本方法。
   * <p>
   * 行不存在、或已经 {@code published=true} 时直接返回，避免重复发 Kafka。
   *
   * @param outboxId outbox 表自增主键
   */
  @Transactional
  public void publishIfPending(long outboxId) {
    Optional<OutboxEntity> row = outboxRepository.findById(outboxId);
    if (row.isEmpty() || row.get().isPublished()) {
      return;
    }
    publishOne(row.get());
  }

  /**
   * 对单行执行「发 Kafka → 标记已发布 → 保存」。调用方应保证 {@code row} 当前仍是未发布状态。
   *
   * @param row 已加载的 outbox 实体，本方法会改 {@code published} 并 {@code save}
   */
  void publishOne(OutboxEntity row) {
    if (row.isPublished()) {
      return;
    }
    publisher.publish(row.getKafkaKey(), row.getPayload());
    row.markPublished();
    outboxRepository.save(row);
  }
}
