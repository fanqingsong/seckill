package io.servicecomb.poc.demo.seckill;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.waitAtMost;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import io.servicecomb.poc.demo.CommandServiceApplication;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.event.SecKillEventType;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = CommandServiceApplication.class)
public class GrabPersistWorkerTest {

  @Autowired
  private SecKillStore store;

  @Autowired
  private Map<String, SecKillCommandService<String>> commandServices;

  @Autowired
  private TransactionalEventOutboxWriter writer;

  @Autowired
  private SecKillEventFormat eventFormat;

  @Autowired
  private SpringSecKillEventRepository eventRepository;

  @Test
  public void drainPersistsGrabbedEvent() {
    PromotionEntity promotion = new PromotionEntity(new Date(), 5, 0.7f);
    store.initStock(promotion.getPromotionId(), 5, Collections.<String>emptySet(), 0);
    commandServices.put(promotion.getPromotionId(),
        new SecKillCommandService<String>(promotion, store, writer, eventFormat, false));

    assertThat(commandServices.get(promotion.getPromotionId()).addCouponTo("c1"), is(SecKillGrabResult.Success));

    waitAtMost(2, SECONDS).until(() -> grabbedCount(promotion.getPromotionId()) >= 1);
    assertThat(eventRepository.findByPromotionId(promotion.getPromotionId()).get(0).getCustomerId(), is("c1"));
  }

  @Test
  public void duplicatePersistStillAcksToken() {
    PromotionEntity promotion = new PromotionEntity(new Date(), 5, 0.7f);
    CouponGrabbedEvent<String> existing = new CouponGrabbedEvent<String>(promotion, "dup");
    writer.persist(eventFormat.toMessage(existing, UUID.randomUUID().toString(), 1L));

    store.initStock(promotion.getPromotionId(), 5, Collections.<String>emptySet(), 1);
    commandServices.put(promotion.getPromotionId(),
        new SecKillCommandService<String>(promotion, store, writer, eventFormat, false));

    int pendingBefore = store.pendingGrabCount();
    assertThat(commandServices.get(promotion.getPromotionId()).addCouponTo("dup"), is(SecKillGrabResult.Success));

    waitAtMost(2, SECONDS).until(() -> store.pendingGrabCount() <= pendingBefore);
    assertThat(grabbedCount(promotion.getPromotionId()), is(1L));
  }

  private long grabbedCount(String promotionId) {
    return eventRepository.findByPromotionId(promotionId).stream()
        .filter(e -> SecKillEventType.CouponGrabbedEvent.equals(e.getType()))
        .count();
  }
}
