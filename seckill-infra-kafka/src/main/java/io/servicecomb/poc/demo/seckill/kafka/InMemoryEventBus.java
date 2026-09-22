package io.servicecomb.poc.demo.seckill.kafka;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryEventBus implements SecKillEventPublisher {

  private static final Logger logger = LoggerFactory.getLogger(InMemoryEventBus.class);
  private final List<SecKillEventListener> listeners = new CopyOnWriteArrayList<SecKillEventListener>();

  public void subscribe(SecKillEventListener listener) {
    listeners.add(listener);
  }

  @Override
  public void publish(String key, String payload) {
    for (SecKillEventListener listener : listeners) {
      listener.onEvent(payload);
    }
  }

  @Override
  public void publishDlt(String key, String payload) {
    logger.warn("DLT key={} payload={}", key, payload);
  }
}
