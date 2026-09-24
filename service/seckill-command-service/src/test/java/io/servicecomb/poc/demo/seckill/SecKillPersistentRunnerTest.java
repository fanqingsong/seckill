/*
 * ┌─ 文件 ────────────────────────────────────┐
 * │ SecKillPersistentRunnerTest.java         │
 * │ 场景：抢券结果要能在事件仓库里查到顾客    │
 * └──────────────────────────────────────────┘
 *
 * 构造顾客 0 的抢券事件
 * │
 * ▼
 * 【本文件】交给 TransactionalEventOutboxWriter.persist
 * │
 * └──▶ Spring 事件仓库里出现该顾客
 *
 * 一句话：只断言事件已写入，不经过 HTTP。
 */
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

/**
 * 守护抢券结果写入事件表：调用 {@link TransactionalEventOutboxWriter#persist} 后，
 * 事件仓库里能按活动编号查到这位顾客。
 * <p>
 * {@code @SpringBootTest} 启动 Command 应用。本文件没有出现 Redis、Kafka、Elasticsearch 或 H2 的类型或配置。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = CommandServiceApplication.class)
public class SecKillPersistentRunnerTest {

  @Autowired
  private TransactionalEventOutboxWriter writer;

  @Autowired
  private SpringSecKillEventRepository eventRepository;

  @Autowired
  private SecKillEventFormat eventFormat;

  /**
   * 前置：新建一个 5 张券的活动，并构造顾客 0 的抢券事件。
   * 动作：把事件交给 writer.persist。
   * 期望：两秒内事件仓库至少有一条，顾客编号是 0。
   */
  @Test
  public void persistGrabWritesEventAndOutbox() {
    PromotionEntity promotion = new PromotionEntity(new Date(), 5, 0.7f);
    CouponGrabbedEvent<String> event = new CouponGrabbedEvent<String>(promotion, "0");
    writer.persist(eventFormat.toMessage(event, UUID.randomUUID().toString(), 1L));

    waitAtMost(2, SECONDS).until(() -> eventRepository.findByPromotionId(promotion.getPromotionId()).size() >= 1);
    assertThat(eventRepository.findByPromotionId(promotion.getPromotionId()).get(0).getCustomerId(), is("0"));
  }
}
