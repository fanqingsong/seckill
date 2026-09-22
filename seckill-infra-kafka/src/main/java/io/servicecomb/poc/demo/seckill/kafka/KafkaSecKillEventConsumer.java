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

public class KafkaSecKillEventConsumer implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(KafkaSecKillEventConsumer.class);

  private final KafkaConsumer<String, String> consumer;
  private final SecKillEventListener listener;
  private final SecKillEventPublisher publisher;
  private final AtomicBoolean running = new AtomicBoolean(true);

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

  @Override
  public void run() {
    while (running.get()) {
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
      for (ConsumerRecord<String, String> record : records) {
        try {
          listener.onEvent(record.value());
          consumer.commitSync();
        } catch (RuntimeException e) {
          logger.error("Projection failed, send DLT. key={}", record.key(), e);
          publisher.publishDlt(record.key(), record.value());
          consumer.commitSync();
        }
      }
    }
  }

  public void stop() {
    running.set(false);
    consumer.wakeup();
  }
}
