package io.servicecomb.poc.demo.seckill.kafka;

import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

public class KafkaSecKillEventPublisher implements SecKillEventPublisher {

  private final KafkaProducer<String, String> producer;

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

  @Override
  public void publish(String key, String payload) {
    send(KafkaTopics.EVENTS, key, payload);
  }

  @Override
  public void publishDlt(String key, String payload) {
    send(KafkaTopics.DLT, key, payload);
  }

  private void send(String topic, String key, String payload) {
    try {
      producer.send(new ProducerRecord<String, String>(topic, key, payload)).get();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to publish to " + topic, e);
    }
  }
}
