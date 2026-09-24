package io.servicecomb.poc.demo.seckill.kafka;

/**
 * Kafka 上两个 topic 的名字。本类不连接 Kafka，只让发布和消费引用同一组常量。
 * <p>
 * {@link #EVENTS} 是正常事件流。Outbox 投递时消息 key 为活动编号 {@code promotionId}，
 * 这样同一活动的开始、抢券、结束会进同一个分区，消费时更容易按序号投影。
 * {@link #DLT} 是死信：Event 服务投影失败时把原消息再发到这里，避免坏消息卡在正常 topic 上反复重试。
 */
public final class KafkaTopics {
  /** 正常事件 topic。payload 是事件消息 JSON。 */
  public static final String EVENTS = "seckill.events";
  /** 死信 topic，名字是正常 topic 加上 {@code .dlt}。 */
  public static final String DLT = "seckill.events.dlt";

  /** 工具类不允许 new。topic 名字通过上面两个常量使用。 */
  private KafkaTopics() {
  }
}
