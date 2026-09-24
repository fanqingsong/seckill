/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ KafkaSecKillEventConsumer.java                │
 * │ 链路：投影 · 消费事件                         │
 * └───────────────────────────────────────────────┘
 *
 * topic seckill.events
 *         │
 *         ▼
 *     【本文件】
 *         ├─ 投影成功
 *         └─ 失败进 .dlt
 *
 * 一句话：消费 seckill.events；失败改送到 .dlt 再提交。
 */

package io.servicecomb.poc.demo.seckill.kafka;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消费 Kafka topic {@code seckill.events}，把每条消息交给 Event 服务投影。
 * <p>
 * 只在 {@code seckill.infra.mode=prod} 时由 Event 服务放到单独线程里 {@link #run}。
 * 投影成功后提交位移。投影抛出运行时异常时，先把原消息发到死信 topic {@code seckill.events.dlt}，
 * 再提交位移，这样坏消息不会留在正常 topic 里无限重试。本类不写 PostgreSQL；读模型和搜索由监听器更新。
 * 查询列表读 Redis，搜索读 Elasticsearch。
 */
public class KafkaSecKillEventConsumer implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(KafkaSecKillEventConsumer.class);

  private final KafkaConsumer<String, String> consumer;
  private final SecKillEventListener listener;
  private final SecKillEventPublisher publisher;
  /** 为 false 时 {@link #run} 的循环结束。{@link #stop} 会改掉它并唤醒可能堵在 poll 上的线程。 */
  private final AtomicBoolean running = new AtomicBoolean(true);

  /**
   * 订阅 {@code seckill.events}，消费者组固定为 {@code seckill-event-service}。
   * <p>
   * 关闭自动提交，改由本类在投影或转入死信之后 {@code commitSync}，避免没处理完就前移位移。
   * {@code auto.offset.reset=earliest} 表示这个组还没有位移时，从 topic 最早的消息读起，
   * 这样 Event 服务晚启动仍能补上已经发出的事件。
   *
   * @param bootstrapServers {@code seckill.kafka.bootstrap}
   * @param listener 投影回调，实现在 Event 服务
   * @param publisher 投影失败时用来发死信。应与正常发布共用同一套 Kafka 配置
   */
  public KafkaSecKillEventConsumer(String bootstrapServers, SecKillEventListener listener,
      SecKillEventPublisher publisher) {
    this.listener = listener;
    this.publisher = publisher;
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "seckill-event-service");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    this.consumer = new KafkaConsumer<String, String>(props);
    this.consumer.subscribe(Collections.singletonList(KafkaTopics.EVENTS));
  }

  /**
   * 循环拉取并投影，直到 {@link #stop}。
   * <p>
   * 每 500 毫秒 poll 一次。同一批里按记录逐条处理：成功就 {@code commitSync}；失败则发死信再提交。
   * 无参 {@code commitSync} 提交的是这次 {@code poll} 整批的位移，不是「只提交当前这一条」。
   * 因此同一批里只要已经成功提交过一次，进程重启后这批剩下的记录不会再次投递。
   */
  @Override
  public void run() {
    while (running.get()) {
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
      for (ConsumerRecord<String, String> record : records) {
        try {
          listener.onEvent(record.value());
          consumer.commitSync();
        } catch (RuntimeException e) {
          // 投影失败：原样写入死信 topic，然后仍提交位移，正常 topic 继续向后读。
          logger.error("Projection failed, send DLT. key={}", record.key(), e);
          publisher.publishDlt(record.key(), record.value());
          consumer.commitSync();
        }
      }
    }
  }

  /**
   * 让 {@link #run} 在下一轮循环条件处退出，并打断正在 poll 的等待。
   * <p>
   * {@code wakeup} 会使阻塞中的 {@code poll} 抛出异常，从而离开 {@link #run}。不提交未处理的记录。
   */
  public void stop() {
    running.set(false);
    consumer.wakeup();
  }
}
