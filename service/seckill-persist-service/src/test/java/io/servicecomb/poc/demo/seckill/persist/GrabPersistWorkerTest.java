package io.servicecomb.poc.demo.seckill.persist;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.waitAtMost;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import io.servicecomb.poc.demo.PersistServiceApplication;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.event.SecKillEventType;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import java.util.Collections;
import java.util.Date;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = PersistServiceApplication.class)
public class GrabPersistWorkerTest {

  @Autowired
  private SecKillStore store;

  @Autowired
  private SpringPromotionRepository promotionRepository;

  @Autowired
  private PersistOutboxWriter writer;

  @Autowired
  private SecKillEventFormat eventFormat;

  @Autowired
  private SpringSecKillEventRepository eventRepository;

  @Test
  public void drainPersistsGrabbedEvent() {
    PromotionEntity promotion = savedPromotion(5);

    assertThat(store.tryGrab(promotion.getPromotionId(), "c1").isSuccess(), is(true));

    waitAtMost(2, SECONDS).until(() -> count(promotion.getPromotionId(), SecKillEventType.CouponGrabbedEvent) >= 1);
    assertThat(eventRepository.findByPromotionId(promotion.getPromotionId()).get(0).getCustomerId(), is("c1"));
  }

  @Test
  public void duplicatePersistStillAcksToken() {
    PromotionEntity promotion = savedPromotion(5);
    CouponGrabbedEvent<String> existing = new CouponGrabbedEvent<String>(promotion, "dup");
    writer.persist(eventFormat.toMessage(existing, UUID.randomUUID().toString(), 1L));

    store.initStock(promotion.getPromotionId(), 5, Collections.<String>emptySet(), 1);
    int pendingBefore = store.pendingGrabCount();
    assertThat(store.tryGrab(promotion.getPromotionId(), "dup").isSuccess(), is(true));

    waitAtMost(2, SECONDS).until(() -> store.pendingGrabCount() <= pendingBefore);
    assertThat(count(promotion.getPromotionId(), SecKillEventType.CouponGrabbedEvent), is(1L));
  }

  @Test
  public void lastCouponWritesFinishEventOnce() {
    PromotionEntity promotion = savedPromotion(1);

    assertThat(store.tryGrab(promotion.getPromotionId(), "last").isSuccess(), is(true));

    waitAtMost(2, SECONDS).until(() -> count(promotion.getPromotionId(), SecKillEventType.PromotionFinishEvent) == 1);
    assertThat(count(promotion.getPromotionId(), SecKillEventType.PromotionFinishEvent), is(1L));
  }

  private PromotionEntity savedPromotion(int coupons) {
    PromotionEntity promotion = promotionRepository.save(new PromotionEntity(new Date(), coupons, 0.7f));
    store.initStock(promotion.getPromotionId(), coupons, Collections.<String>emptySet(), 0);
    return promotion;
  }

  private long count(String promotionId, String type) {
    return eventRepository.findByPromotionId(promotionId).stream()
        .filter(event -> type.equals(event.getType()))
        .count();
  }
}
