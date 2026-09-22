package io.servicecomb.poc.demo.seckill.kafka;

public interface SecKillEventListener {
  void onEvent(String payload);
}
