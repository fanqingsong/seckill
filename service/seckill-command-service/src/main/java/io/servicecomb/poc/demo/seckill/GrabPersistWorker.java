package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.redis.GrabToken;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class GrabPersistWorker {

  private static final Logger logger = LoggerFactory.getLogger(GrabPersistWorker.class);
  private static final int BATCH = 50;

  private final SecKillStore store;
  private final Map<String, SecKillCommandService<String>> commandServices;

  public GrabPersistWorker(SecKillStore store, Map<String, SecKillCommandService<String>> commandServices) {
    this.store = store;
    this.commandServices = commandServices;
    Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(this::drain, 50, 50, TimeUnit.MILLISECONDS);
  }

  void drain() {
    try {
      for (int i = 0; i < BATCH; i++) {
        if (!drainOne()) {
          return;
        }
      }
    } catch (RuntimeException e) {
      logger.warn("Grab persist worker failed: {}", e.getMessage());
    }
  }

  boolean drainOne() {
    GrabToken token = store.pollInflight();
    if (token == null) {
      return false;
    }
    SecKillCommandService<String> service = commandServices.get(token.getPromotionId());
    if (service == null) {
      store.deferInflight(token);
      return false;
    }
    try {
      service.persistGrab(token);
      store.ackGrab(token);
      if (token.getRemaining() <= 0) {
        service.finish();
      }
      return true;
    } catch (DataIntegrityViolationException duplicate) {
      store.ackGrab(token);
      if (token.getRemaining() <= 0) {
        service.finish();
      }
      return true;
    } catch (RuntimeException e) {
      logger.warn("Persist grab failed, will retry. customer={}", token.getCustomerId(), e);
      return false;
    }
  }
}
