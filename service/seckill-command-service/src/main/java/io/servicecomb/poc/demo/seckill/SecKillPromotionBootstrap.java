package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.PromotionStartEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SecKillPromotionBootstrap<T> {

  private static final Logger logger = LoggerFactory.getLogger(SecKillPromotionBootstrap.class);

  private final SpringPromotionRepository promotionRepository;
  private final Map<String, SecKillCommandService<T>> commandServices;
  private final SecKillRecoveryService<T> recoveryService;
  private final SecKillStore store;
  private final TransactionalEventOutboxWriter writer;
  private final SecKillEventFormat eventFormat;

  private final Map<String, PromotionEntity> waitingPromotions = new HashMap<String, PromotionEntity>();
  private int loadedPromotionId = 0;

  public SecKillPromotionBootstrap(
      SpringPromotionRepository promotionRepository,
      Map<String, SecKillCommandService<T>> commandServices,
      SecKillRecoveryService<T> recoveryService,
      SecKillStore store,
      TransactionalEventOutboxWriter writer,
      SecKillEventFormat eventFormat) {
    this.promotionRepository = promotionRepository;
    this.commandServices = commandServices;
    this.recoveryService = recoveryService;
    this.store = store;
    this.writer = writer;
    this.eventFormat = eventFormat;
  }

  public void run() {
    Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(this::tick, 0, 500, TimeUnit.MILLISECONDS);
  }

  private void tick() {
    Iterable<PromotionEntity> promotions = promotionRepository.findByIdGreaterThan(loadedPromotionId);
    for (PromotionEntity promotion : promotions) {
      if (promotion.getPublishTime().getTime() <= System.currentTimeMillis()) {
        startUpPromotion(promotion);
        logger.info("PromotionEntity started = {}", promotion);
      } else {
        waitingPromotions.put(promotion.getPromotionId(), promotion);
      }
      loadedPromotionId = promotion.getId();
    }

    for (String promotionId : new HashSet<String>(waitingPromotions.keySet())) {
      PromotionEntity promotion = waitingPromotions.get(promotionId);
      if (promotion.getPublishTime().getTime() <= System.currentTimeMillis()) {
        startUpPromotion(promotion);
        logger.info("PromotionEntity started = {}", promotion);
        waitingPromotions.remove(promotionId);
      }
    }

    for (SecKillCommandService<T> service : commandServices.values()) {
      // finishTime handled inside addCouponTo; still emit finish when time reached
    }
    finishExpired();
  }

  private void finishExpired() {
    for (PromotionEntity promotion : promotionRepository.findAll()) {
      SecKillCommandService<T> service = commandServices.get(promotion.getPromotionId());
      if (service != null && promotion.getFinishTime().getTime() <= System.currentTimeMillis()
          && store.pendingGrabCount() == 0) {
        service.finish();
      }
    }
  }

  private void startUpPromotion(PromotionEntity promotion) {
    if (commandServices.containsKey(promotion.getPromotionId())) {
      return;
    }
    SecKillRecoveryCheckResult<T> recoveryInfo = recoveryService.check(promotion);
    if (!store.stockKeysPresent(promotion.getPromotionId())) {
      Set<String> claimed = new HashSet<String>();
      for (T customer : recoveryInfo.getClaimedCustomers()) {
        claimed.add(String.valueOf(customer));
      }
      store.initStock(promotion.getPromotionId(), recoveryInfo.remainingCoupons(), claimed, recoveryInfo.lastSeq());
      if (!recoveryInfo.isStarted()) {
        long seq = store.nextSeq(promotion.getPromotionId());
        EventMessageDto start = eventFormat.toMessage(new PromotionStartEvent(promotion), UUID.randomUUID().toString(),
            seq);
        writer.persist(start);
      }
    }
    SecKillCommandService<T> service = new SecKillCommandService<T>(promotion, store, writer, eventFormat,
        recoveryInfo.isFinished());
    commandServices.put(promotion.getPromotionId(), service);
  }
}
