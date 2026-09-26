/*
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ SpringOutboxRepository.java                          │
 * │ 链路：outbox 投递                                    │
 * └──────────────────────────────────────────────────────┘
 *
 *   与事件同一事务里 save
 *      │
 *      ▼
 *   【本文件】取出尚未发布的最早 50 行
 *      │
 *      ▼
 *   relay 发往 Kafka seckill.events
 *
 * 一句话：只读写 PostgreSQL 的 outbox，不读 Redis。
 */

package io.servicecomb.poc.demo.seckill.repositories.spring;

import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PostgreSQL 表 {@code outbox} 的 Spring Data 仓库。
 * <p>
 * Command 和 Persist 在与事件同一事务里 {@code save} 新行。Outbox Relay 服务
 * 调用 {@link #findTop50ByPublishedFalseOrderByIdAsc()}，发到 Kafka 的 topic
 * {@code seckill.events} 后再把行标成已发布并保存。本接口不读 Redis。
 * <p>
 * {@code JpaRepository<OutboxEntity, Long>} 表示实体是 {@link OutboxEntity}，主键是 {@code Long}。
 * 方法名里的 {@code Top50} 表示最多 50 条，{@code PublishedFalse} 表示 {@code published = false}，
 * {@code OrderByIdAsc} 表示按自增 id 从早到晚。Spring 根据这段英文生成查询，接口里没有 SQL。
 */
public interface SpringOutboxRepository extends JpaRepository<OutboxEntity, Long> {

  /**
   * 取出尚未发到 Kafka 的最早 50 行。
   *
   * @return 未发布的 outbox，按 id 升序，可能少于 50 条。不修改这些行
   */
  List<OutboxEntity> findTop50ByPublishedFalseOrderByIdAsc();
}
