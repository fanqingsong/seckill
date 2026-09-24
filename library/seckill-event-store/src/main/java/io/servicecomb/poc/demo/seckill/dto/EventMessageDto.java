/*
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ EventMessageDto.java                                 │
 * │ 链路：outbox 到 Kafka                                │
 * └──────────────────────────────────────────────────────┘
 *
 *   Command / Persist 组装消息
 *      │
 *      ▼
 *   【本文件】eventId、type、seq、content
 *      │
 *      ▼
 *   PostgreSQL outbox，relay 再发 Kafka
 *
 * 一句话：本类只放待发字段，插入 outbox 的是调用方。
 */

package io.servicecomb.poc.demo.seckill.dto;

/**
 * 放进 outbox、再发到 Kafka topic {@code seckill.events} 的一条事件消息。
 * <p>
 * Command 和 Persist 先得到本对象，序列化后写入 PostgreSQL 的 outbox；relay 把它发出去。
 * Event 服务反序列化后投影到 Redis 和 Elasticsearch。字段是 {@code protected}，
 * 同一包或子类能直接读，JSON 工具按字段读写（见 {@link io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat}）。
 * 本类不写任何存储。
 */
public class EventMessageDto {

  /** 事件业务编号，与事件表 {@code eventId} 相同。 */
  protected String eventId;
  /** 活动编号，也是 Kafka 消息的 key。 */
  protected String promotionId;
  /** 活动内顺序号。0 表示投影时不按序号缺口处理。 */
  protected long seq;
  /** 三种事件的简单类名之一。 */
  protected String type;
  /** 组装消息时的毫秒时间。 */
  protected long occurredAt;
  /** 事件正文 JSON：活动或券，不是整条消息本身。 */
  protected String content;
  /**
   * 抢券时是顾客编号；开始、结束分别是 {@code __start__}、{@code __finish__}。
   * 用来满足事件表上 {@code (promotionId, customerId)} 的唯一约束。
   */
  protected String customerId;

  /**
   * JSON 反序列化需要的无参构造器。业务组装用下面两个构造器。
   */
  public EventMessageDto() {
  }

  /**
   * 只有类型、活动和正文时使用。{@code eventId} 和 {@code customerId} 为 null，序号为 0，
   * 时间为当前毫秒。不写数据库。
   *
   * @param type 事件种类
   * @param promotionId 活动编号
   * @param content 正文 JSON
   */
  public EventMessageDto(String type, String promotionId, String content) {
    this(null, promotionId, 0L, type, System.currentTimeMillis(), content, null);
  }

  /**
   * 用调用方已经定好的编号和序号组装一条消息。
   *
   * @param eventId 事件业务编号
   * @param promotionId 活动编号
   * @param seq 顺序号
   * @param type 事件种类
   * @param occurredAt 毫秒时间
   * @param content 正文 JSON
   * @param customerId 顾客编号或开始/结束占位
   */
  public EventMessageDto(String eventId, String promotionId, long seq, String type, long occurredAt, String content,
      String customerId) {
    this.eventId = eventId;
    this.promotionId = promotionId;
    this.seq = seq;
    this.type = type;
    this.occurredAt = occurredAt;
    this.content = content;
    this.customerId = customerId;
  }

  /** 事件业务编号。 */
  public String getEventId() {
    return eventId;
  }

  /** 活动编号。 */
  public String getPromotionId() {
    return promotionId;
  }

  /** 活动内顺序号。 */
  public long getSeq() {
    return seq;
  }

  /** 事件种类。 */
  public String getType() {
    return type;
  }

  /** 发生时间的毫秒值。 */
  public long getOccurredAt() {
    return occurredAt;
  }

  /** 正文 JSON。 */
  public String getContent() {
    return content;
  }

  /** 顾客编号或开始/结束占位。 */
  public String getCustomerId() {
    return customerId;
  }
}
