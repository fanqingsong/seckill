/*
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ OutboxEntity.java                                    │
 * │ 链路：事务性 outbox                                  │
 * └──────────────────────────────────────────────────────┘
 *
 *   与事件行处在同一事务
 *      │
 *      ▼
 *   【本文件】eventId、kafkaKey、payload
 *      │
 *      ▼
 *   提交后 relay 发 Kafka 再标已发布
 *
 * 一句话：先落 PostgreSQL 再发消息，本类不直接访问 Kafka。
 */

package io.servicecomb.poc.demo.seckill.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 事务性 outbox 表里的一行，对应稍后要发到 Kafka 的一条消息。
 * <p>
 * Command 服务在写入 {@code PromotionStartEvent}、{@code PromotionFinishEvent} 时，
 * Persist 服务在写入 {@code CouponGrabbedEvent} 时，都会和事件行放在同一个 PostgreSQL
 * 事务里插入本类。事务提交之后，Command 里的 outbox relay 调用 {@link #markPublished()}
 * 再把 {@code published} 改成 true。本类不访问 Redis，也不直接发 Kafka。
 * 抢券 HTTP 线程不会插入这一行；HTTP 返回成功时，这里可能还没有对应记录。
 * <p>
 * {@code @Entity} 告诉 JPA：这个类对应一张数据库表。{@code @Table(name = "outbox")}
 * 指定表名是 {@code outbox}，不使用类名当表名。{@code @Id} 标出主键。
 * {@code @GeneratedValue(strategy = GenerationType.IDENTITY)} 表示主键由数据库自增，
 * 插入成功后才回填到 {@link #id}，Java 代码不要自己编这个数字。
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

  /** 数据库自增主键，插入前是 null。 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 与事件表 {@code eventId} 相同，用来把这条待发消息和那一行事件对上。 */
  private String eventId;

  /** 发往 Kafka 时使用的 key，调用方传入的是活动编号 {@code promotionId}。 */
  private String kafkaKey;

  /**
   * 整条消息的 JSON。{@code @Column(length = 8000)} 把这一列的长度限制在 8000。
   */
  @Column(length = 8000)
  private String payload;

  /** 本行插入时的毫秒时间戳，构造时取 {@code System.currentTimeMillis()}。 */
  private long createdAt;

  /** 尚未被 relay 发到 Kafka 时为 false。发成功后由 {@link #markPublished()} 改为 true。 */
  private boolean published;

  /**
   * JPA 需要的无参构造器。反射创建空对象后再填字段，业务代码不要用它表达一条新消息。
   */
  public OutboxEntity() {
  }

  /**
   * 准备一条尚未发布的 outbox。不写数据库，要等调用方 {@code save}。
   *
   * @param eventId 事件编号，与事件表那一行相同
   * @param kafkaKey Kafka 消息 key，现有调用传入活动编号
   * @param payload 已序列化的消息正文
   */
  public OutboxEntity(String eventId, String kafkaKey, String payload) {
    this.eventId = eventId;
    this.kafkaKey = kafkaKey;
    this.payload = payload;
    this.createdAt = System.currentTimeMillis();
    this.published = false;
  }

  /** 自增主键。 */
  public Long getId() {
    return id;
  }

  /** 对应的事件编号。 */
  public String getEventId() {
    return eventId;
  }

  /** Kafka 消息 key。 */
  public String getKafkaKey() {
    return kafkaKey;
  }

  /** 待发送的 JSON 正文。 */
  public String getPayload() {
    return payload;
  }

  /** 插入时的毫秒时间戳。 */
  public long getCreatedAt() {
    return createdAt;
  }

  /** 是否已经发到 Kafka。 */
  public boolean isPublished() {
    return published;
  }

  /**
   * 把本行标成已发布。只改内存里的字段；调用方还要 {@code save}，PostgreSQL 才会更新。
   * 不发 Kafka，也不改事件表。
   */
  public void markPublished() {
    this.published = true;
  }
}
