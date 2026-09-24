/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ KafkaSecKillEventPublisher.java               │
 * │ 链路：投递 · Kafka 发布                       │
 * └───────────────────────────────────────────────┘
 *
 * OutboxRelay
 *      │
 *      ▼
 *  【本文件】
 *      │
 *      ▼
 * seckill.events / .dlt
 *
 * 一句话：mode 等于 prod 时同步发到 seckill.events。
 */

package io.servicecomb.poc.demo.seckill.kafka;

import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * {@link SecKillEventPublisher} 的 Kafka 实现。{@code seckill.infra.mode=prod} 时由配置类创建。
 * <p>
 * 正常消息发到 topic {@code seckill.events}，死信发到 {@code seckill.events.dlt}。
 * key 和 value 都是字符串。key 使用活动编号，让同一活动进入同一分区。
 * 发送是同步的：{@code send(...).get()} 要等 broker 确认后才返回，失败则抛给 OutboxRelay，
 * 那一行 outbox 保持未发布，下一轮再试。本类不写数据库，也不改 Redis 库存。
 */
public class KafkaSecKillEventPublisher implements SecKillEventPublisher {

  private final KafkaProducer<String, String> producer;

  /**
   * 按给定的 broker 地址创建生产者。
   * <p>
   * {@code acks=all} 表示要等所有同步副本确认。幂等开启后，配合最多 3 次重试，
   * 同一条消息在重试时不会在分区里变成多条。序列化用字符串，和 outbox 里存的 JSON 文本一致。
   *
   * @param bootstrapServers {@code seckill.kafka.bootstrap}，例如 {@code 127.0.0.1:9092}
   */
  public KafkaSecKillEventPublisher(String bootstrapServers) {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
    props.put(ProducerConfig.RETRIES_CONFIG, 3);
    this.producer = new KafkaProducer<String, String>(props);
  }

  /**
   * 发到正常 topic {@link KafkaTopics#EVENTS}。
   *
   * @param key 活动编号
   * @param payload 事件消息 JSON
   */
  @Override
  public void publish(String key, String payload) {
    send(KafkaTopics.EVENTS, key, payload);
  }

  /**
   * 发到死信 topic {@link KafkaTopics#DLT}。
   *
   * @param key 原消息的活动编号
   * @param payload 原消息正文
   */
  @Override
  public void publishDlt(String key, String payload) {
    send(KafkaTopics.DLT, key, payload);
  }

  /**
   * 同步发送一条记录。{@code get()} 会等到 broker 确认或抛出异常。
   *
   * @param topic {@code seckill.events} 或 {@code seckill.events.dlt}
   * @param key 分区键
   * @param payload 消息正文
   * @throws IllegalStateException 发送或等待确认失败时抛出，原因保留在 cause 里
   */
  private void send(String topic, String key, String payload) {
    try {
      producer.send(new ProducerRecord<String, String>(topic, key, payload)).get();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to publish to " + topic, e);
    }
  }
}
