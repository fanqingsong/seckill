package io.servicecomb.poc.demo.seckill.kafka;

public interface SecKillEventPublisher {
  void publish(String key, String payload);

  void publishDlt(String key, String payload);
}
