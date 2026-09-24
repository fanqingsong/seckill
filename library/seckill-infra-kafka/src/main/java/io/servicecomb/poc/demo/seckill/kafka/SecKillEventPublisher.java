/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ SecKillEventPublisher.java                    │
 * │ 链路：投递 · 发布契约                         │
 * └───────────────────────────────────────────────┘
 *
 * OutboxRelay
 *      │
 *      ▼
 *  【本文件】
 *      ├─ seckill.events
 *      └─ seckill.events.dlt
 *
 * 一句话：把已提交的 outbox 发到 topic seckill.events。
 */

package io.servicecomb.poc.demo.seckill.kafka;

/**
 * 把已经提交到 outbox 的事件发到 Kafka。
 * <p>
 * Command 和 Persist 先在数据库事务里写下事件和 outbox，OutboxRelay 再调用本接口。
 * 抢券 HTTP 不调用它。{@code seckill.infra.mode=prod} 时实现是 {@link KafkaSecKillEventPublisher}，
 * 其它值（缺省 {@code memory}）是 {@link InMemoryEventBus}。两种实现都不写 Redis，也不改库存。
 */
public interface SecKillEventPublisher {
  /**
   * 把一条事件发到正常 topic {@code seckill.events}。
   *
   * @param key 分区键，outbox 里存的是活动编号 {@code promotionId}
   * @param payload 事件消息 JSON。接收方是 Event 服务，由它投影 Redis 读模型和 Elasticsearch
   */
  void publish(String key, String payload);

  /**
   * 把一条投影失败的消息发到死信 topic {@code seckill.events.dlt}。
   *
   * @param key 原消息的分区键，一般仍是活动编号
   * @param payload 原消息正文。本方法不负责再投影
   */
  void publishDlt(String key, String payload);
}
