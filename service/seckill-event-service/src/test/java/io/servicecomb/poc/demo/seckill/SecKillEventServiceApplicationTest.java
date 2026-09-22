package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import io.servicecomb.poc.demo.EventServiceApplication;
import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionFinishEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionStartEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = EventServiceApplication.class)
public class SecKillEventServiceApplicationTest {

  @Autowired
  private EventProjector projector;

  @Autowired
  private SecKillStore store;

  @Autowired
  private SecKillEventFormat eventFormat;

  private final PromotionEntity promotion1 = generatePromotion();
  private final PromotionEntity promotion2 = generatePromotion();

  @Test
  public void receivePromotionEventMessage() {
    projector.project(message(new PromotionStartEvent(promotion1), 1));
    projector.project(message(new PromotionStartEvent(promotion2), 1));

    List<PromotionEntity> promotions = new ArrayList<PromotionEntity>(store.activePromotions());
    assertThat(promotions.size(), is(2));
    assertThat(promotions, containsInAnyOrder(hasProperty("promotionId", is(promotion1.getPromotionId())),
        hasProperty("promotionId", is(promotion2.getPromotionId()))));

    projector.project(message(new PromotionFinishEvent(promotion1), 2));
    promotions = new ArrayList<PromotionEntity>(store.activePromotions());
    assertThat(promotions.size(), is(1));
    assertThat(promotions, contains(hasProperty("promotionId", is(promotion2.getPromotionId()))));
  }

  @Test
  public void projectCouponGrab() {
    projector.project(message(new CouponGrabbedEvent<String>(promotion1, "zyy"), 1));
    List<CouponEntity<String>> coupons = new ArrayList<CouponEntity<String>>(store.customerCoupons("zyy"));
    assertThat(coupons.size(), is(1));
    assertThat(coupons.get(0).getCustomerId(), is("zyy"));
  }

  private EventMessageDto message(io.servicecomb.poc.demo.seckill.event.SecKillEvent event, long seq) {
    return eventFormat.toMessage(event, UUID.randomUUID().toString(), seq);
  }

  private PromotionEntity generatePromotion() {
    return new PromotionEntity(new Date(), new Date(System.currentTimeMillis() + 24 * 3600 * 1000), 10, 0.8f);
  }
}
