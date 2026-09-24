package io.servicecomb.poc.demo.seckill.kafka;

/**
 * 正常 topic {@code seckill.events} 上的一条消息到达后，由 Event 服务实现的回调。
 * <p>
 * 生产环境由 {@link KafkaSecKillEventConsumer} 在独立线程里调用；内存模式由
 * {@link InMemoryEventBus} 在发布的同一线程里调用。回调里应把事件投影到 Redis 读模型和
 * Elasticsearch。查询列表读 Redis，搜索读 Elasticsearch。本接口本身不访问这两种存储。
 */
public interface SecKillEventListener {
  /**
   * 处理一条已经从 Kafka（或内存总线）取出的事件正文。
   *
   * @param payload 事件消息 JSON。分区键不在这里传递，投影所需的活动编号在正文里
   * @throws RuntimeException 投影失败时抛出。Kafka 消费端会把原消息送进死信 topic 并提交位移
   */
  void onEvent(String payload);
}
