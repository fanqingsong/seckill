/*
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ EventEntity.java                                     │
 * │ 链路：追加事件行                                     │
 * └──────────────────────────────────────────────────────┘
 *
 *   Command 开始/结束，Persist 抢到券
 *      │
 *      ▼
 *   【本文件】sec_kill_event 的一行
 *      │
 *      ▼
 *   与 outbox 同一事务；回放再读
 *
 * 一句话：事件只追加；抢券 HTTP 线程不插入本表。
 */

package io.servicecomb.poc.demo.seckill.entities;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 追加进 PostgreSQL 表 {@code sec_kill_event} 的一行事件。事件只新增，不改成一张可变的库存表。
 * <p>
 * Command 服务写入 {@code PromotionStartEvent}，以及到点或卖完时的 {@code PromotionFinishEvent}。
 * Persist 服务在从 Redis 抢券队列取出令牌后写入 {@code CouponGrabbedEvent}。两者都和 outbox
 * 处在同一个数据库事务里。Event 服务回放时按活动编号和 {@code seq} 读这些行，再投影到
 * Redis 和 Elasticsearch。抢券 HTTP 线程不插入本表。
 * <p>
 * {@code @Entity} 表示这是 JPA 实体。{@code @Table} 指定表名，并声明唯一约束
 * {@code uk_grab_customer}：同一 {@code promotionId} 加同一 {@code customerId} 只能有一行。
 * 抢券事件的 {@code customerId} 是真实顾客；开始、结束事件在转换时分别写成
 * {@code __start__} 和 {@code __finish__}，这样三种事件不会互相挤掉，同一顾客也不能抢两次。
 * {@code @Id} 配 {@code GenerationType.IDENTITY} 表示 {@link #id} 由数据库自增。
 * {@code @Column(unique = true)} 要求 {@link #eventId} 全表不重复。
 * {@code @Index} 给两类查询建索引：按活动加类型判断是否已经开始或结束，
 * 按活动加序号回放。索引不改变列，也不改变「只追加」的写法。
 */
@Entity
@Table(name = "sec_kill_event", uniqueConstraints = {
    @UniqueConstraint(name = "uk_grab_customer", columnNames = {"promotionId", "customerId"})
}, indexes = {
    @Index(name = "idx_event_promotion_type", columnList = "promotionId,type"),
    @Index(name = "idx_event_promotion_seq", columnList = "promotionId,seq")
})
public class EventEntity {

  /** 数据库自增主键，和业务上的 {@link #eventId} 不是同一个值。 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private int id;

  /** 事件的业务编号，outbox 用它和本行对应。 */
  @Column(unique = true)
  private String eventId;

  /**
   * 同一活动内的顺序号。回放按它从小到大重放。为 0 时表示这条消息不参与序号缺口判断。
   */
  private long seq;

  /**
   * 事件种类的简单类名：{@code PromotionStartEvent}、{@code CouponGrabbedEvent}
   * 或 {@code PromotionFinishEvent}。
   */
  private String type;

  /** 这场活动的编号。Kafka 消息的 key 也是它。 */
  private String promotionId;

  /**
   * 唯一约束的另一半。抢券是顾客编号；开始、结束是固定占位，不是真实顾客。
   */
  private String customerId;

  /** 事件发生时的毫秒时间戳。 */
  private long occurredAt;

  /** 事件正文 JSON，最长 8000。开始和结束存活动，抢到券存 {@code CouponEntity}。 */
  @Column(length = 8000)
  private String content;

  /**
   * JPA 用的无参构造器。追加事件请用下面两个构造器。
   */
  public EventEntity() {
  }

  /**
   * 只知道类型、活动和正文时使用。{@code eventId} 新生成，{@code seq} 为 0，
   * {@code customerId} 为 null，时间为当前毫秒。不写数据库。
   *
   * @param type 三种事件名之一
   * @param promotionId 活动编号
   * @param content 已序列化的正文
   */
  public EventEntity(String type, String promotionId, String content) {
    this(UUID.randomUUID().toString(), 0L, type, promotionId, null, content, System.currentTimeMillis());
  }

  /**
   * 用消息里已经定好的编号、序号和时间组装一行。不写数据库，调用方再 {@code save}。
   *
   * @param eventId 事件业务编号
   * @param seq 活动内顺序号
   * @param type 事件种类
   * @param promotionId 活动编号
   * @param customerId 顾客编号或开始/结束占位，可以为 null
   * @param content 正文 JSON
   * @param occurredAt 发生时间的毫秒值
   */
  public EventEntity(String eventId, long seq, String type, String promotionId, String customerId, String content,
      long occurredAt) {
    this.eventId = eventId;
    this.seq = seq;
    this.type = type;
    this.promotionId = promotionId;
    this.customerId = customerId;
    this.content = content;
    this.occurredAt = occurredAt;
  }

  /** 自增主键。 */
  public int getId() {
    return id;
  }

  /** 事件业务编号。 */
  public String getEventId() {
    return eventId;
  }

  /** 活动内顺序号。 */
  public long getSeq() {
    return seq;
  }

  /** 事件种类。 */
  public String getType() {
    return type;
  }

  /** 活动编号。 */
  public String getPromotionId() {
    return promotionId;
  }

  /** 顾客编号或开始/结束占位。 */
  public String getCustomerId() {
    return customerId;
  }

  /** 发生时间的毫秒值。 */
  public long getOccurredAt() {
    return occurredAt;
  }

  /** 正文 JSON。 */
  public String getContent() {
    return content;
  }
}
