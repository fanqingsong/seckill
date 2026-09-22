package io.servicecomb.poc.demo.seckill;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.waitAtMost;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import io.servicecomb.poc.demo.CommandServiceApplication;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import java.util.Date;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = CommandServiceApplication.class)
public class SecKillPersistentRunnerTest {

  @Autowired
  private TransactionalEventOutboxWriter writer;

  @Autowired
  private SpringSecKillEventRepository eventRepository;

  @Autowired
  private SecKillEventFormat eventFormat;

  @Test
  public void persistGrabWritesEventAndOutbox() {
    PromotionEntity promotion = new PromotionEntity(new Date(), 5, 0.7f);
    CouponGrabbedEvent<String> event = new CouponGrabbedEvent<String>(promotion, "0");
    writer.persist(eventFormat.toMessage(event, UUID.randomUUID().toString(), 1L));

    waitAtMost(2, SECONDS).until(() -> eventRepository.findByPromotionId(promotion.getPromotionId()).size() >= 1);
    assertThat(eventRepository.findByPromotionId(promotion.getPromotionId()).get(0).getCustomerId(), is("0"));
  }
}
