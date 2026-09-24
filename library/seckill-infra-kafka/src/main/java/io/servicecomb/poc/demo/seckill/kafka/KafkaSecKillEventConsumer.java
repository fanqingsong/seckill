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
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
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
   * 每 500 毫秒 poll 一次。同一批里按记录逐条处理：投影成功，或已经写进死信，才把这条的位移记下来。
   * 整批处理完再 {@code commitSync} 一次，提交的是「下一条要读的位置」，不是还没处理的后半批。
   * 这样进程在本批中途崩溃时，还没投影、也没进死信的记录会再次投递。投影本身按序号去重。
   */
  @Override
  public void run() {
    while (running.get()) {
      ConsumerRecords<String, String> records;
      try {
        records = consumer.poll(Duration.ofMillis(500));
      } catch (WakeupException wakeup) {
        // stop() 会 wakeup，让堵在 poll 上的线程立刻出来。这一轮没有新位移要提交。
        break;
      }
      Map<TopicPartition, OffsetAndMetadata> done = new HashMap<TopicPartition, OffsetAndMetadata>();
      for (ConsumerRecord<String, String> record : records) {
        try {
          listener.onEvent(record.value());
        } catch (RuntimeException e) {
          // 投影失败：原样写入死信 topic。死信也失败时，先提交本条之前的位移，本条下次再投递。
          logger.error("Projection failed, send DLT. key={}", record.key(), e);
          try {
            publisher.publishDlt(record.key(), record.value());
          } catch (RuntimeException dltFailed) {
            commitProcessed(done);
            throw dltFailed;
          }
        }
        // offset + 1 是下一条要读的位置。同一分区后面的记录会覆盖前面的，因为处理顺序就是记录顺序。
        done.put(new TopicPartition(record.topic(), record.partition()),
            new OffsetAndMetadata(record.offset() + 1));
      }
      commitProcessed(done);
    }
  }

  /**
   * 提交已经投影或已转入死信的位移。没有处理完任何记录时不提交。
   *
   * @param done 分区到下一条位移。空表直接返回
   */
  private void commitProcessed(Map<TopicPartition, OffsetAndMetadata> done) {
    if (done.isEmpty()) {
      return;
    }
    consumer.commitSync(done);
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
