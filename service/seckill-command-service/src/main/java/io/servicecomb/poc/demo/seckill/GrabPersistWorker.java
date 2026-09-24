package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.redis.GrabToken;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class GrabPersistWorker {

  private static final Logger logger = LoggerFactory.getLogger(GrabPersistWorker.class);
  private static final long POLL_TIMEOUT_MS = 1000;
  private static final long RETRY_BACKOFF_MS = 50;

  private final SecKillStore store;
  private final Map<String, SecKillCommandService<String>> commandServices;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ExecutorService executor;

  public GrabPersistWorker(SecKillStore store, Map<String, SecKillCommandService<String>> commandServices) {
    this.store = store;
    this.commandServices = commandServices;
    this.executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "grab-persist");
      thread.setDaemon(true);
      return thread;
    });
    executor.execute(this::loop);
  }

  @PreDestroy
  public void stop() {
    running.set(false);
    executor.shutdownNow();
  }

  void loop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        drainOne();
      } catch (RuntimeException e) {
        logger.warn("Grab persist worker failed: {}", e.getMessage());
        sleepBackoff();
      }
    }
  }

  boolean drainOne() {
    GrabToken token = store.pollInflight(POLL_TIMEOUT_MS);
    if (token == null) {
      return false;
    }
    SecKillCommandService<String> service = commandServices.get(token.getPromotionId());
    if (service == null) {
      store.deferInflight(token);
      sleepBackoff();
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
      sleepBackoff();
      return false;
    }
  }

  private void sleepBackoff() {
    try {
      Thread.sleep(RETRY_BACKOFF_MS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
