package io.servicecomb.poc.demo.seckill.persist;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionFinishEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.redis.GrabToken;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
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
  private final SpringPromotionRepository promotionRepository;
  private final PersistOutboxWriter writer;
  private final SecKillEventFormat eventFormat;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ExecutorService executor;

  public GrabPersistWorker(SecKillStore store, SpringPromotionRepository promotionRepository,
      PersistOutboxWriter writer, SecKillEventFormat eventFormat) {
    this.store = store;
    this.promotionRepository = promotionRepository;
    this.writer = writer;
    this.eventFormat = eventFormat;
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
    PromotionEntity promotion = promotionRepository.findTopByPromotionId(token.getPromotionId());
    if (promotion == null) {
      store.deferInflight(token);
      sleepBackoff();
      return false;
    }
    try {
      writer.persist(grabbedMessage(promotion, token));
      store.ackGrab(token);
      finishIfLast(promotion, token);
      return true;
    } catch (DataIntegrityViolationException duplicate) {
      store.ackGrab(token);
      finishIfLast(promotion, token);
      return true;
    } catch (RuntimeException e) {
      logger.warn("Persist grab failed, will retry. customer={}", token.getCustomerId(), e);
      sleepBackoff();
      return false;
    }
  }

  private EventMessageDto grabbedMessage(PromotionEntity promotion, GrabToken token) {
    CouponGrabbedEvent<String> event = new CouponGrabbedEvent<String>(promotion, token.getCustomerId());
    return eventFormat.toMessage(event, UUID.randomUUID().toString(), token.getSeq());
  }

  private void finishIfLast(PromotionEntity promotion, GrabToken token) {
    if (token.getRemaining() > 0 || writer.hasFinishEvent(promotion.getPromotionId())) {
      return;
    }
    try {
      PromotionFinishEvent event = new PromotionFinishEvent(promotion);
      long seq = store.nextSeq(promotion.getPromotionId());
      writer.persist(eventFormat.toMessage(event, UUID.randomUUID().toString(), seq));
    } catch (DataIntegrityViolationException duplicate) {
      logger.info("Finish event already stored for {}", promotion.getPromotionId());
    } catch (RuntimeException e) {
      logger.warn("Failed to persist finish event {}", promotion.getPromotionId(), e);
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
