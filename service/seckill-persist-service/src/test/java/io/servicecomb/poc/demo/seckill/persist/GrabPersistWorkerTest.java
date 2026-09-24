/*
 * ┌─ 文件 ───────────────────────────────────┐
 * │ GrabPersistWorkerTest.java              │
 * │ 场景：扣库存之后，后台写成抢券或结束事件 │
 * └─────────────────────────────────────────┘
 *
 * tryGrab 成功，或同一顾客再抢一次
 * │
 * ▼
 * 【本文件】Persist 从 SecKillStore 取走结果
 * │
 * ├── 新顾客 ──▶ 事件表有 CouponGrabbedEvent
 * ├── 重复顾客 ──▶ 仍只有 1 条，待处理条数回落
 * └── 最后一张 ──▶ PromotionFinishEvent 恰好 1 条
 *
 * 一句话：断言事件仓库，不经过浏览器。
 */
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

/**
 * 守护 Persist 服务：库存扣减成功后，后台把抢券结果写成事件；卖完时再写一条结束事件。
 * <p>
 * {@code @SpringBootTest} 启动 Persist 应用。库存通过 {@link SecKillStore} 扣减，事件从 Spring 仓库读回。
 * 本文件没有出现 Kafka、Elasticsearch 或 H2 的类型或配置。
 */
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

  /**
   * 前置：活动有 5 张券，库存已初始化。
   * 动作：顾客 c1 调用 tryGrab 并成功。
   * 期望：两秒内事件表出现至少一条 CouponGrabbedEvent，顾客编号是 c1。
   */
  @Test
  public void drainPersistsGrabbedEvent() {
    PromotionEntity promotion = savedPromotion(5);

    assertThat(store.tryGrab(promotion.getPromotionId(), "c1").isSuccess(), is(true));

    waitAtMost(2, SECONDS).until(() -> count(promotion.getPromotionId(), SecKillEventType.CouponGrabbedEvent) >= 1);
    assertThat(eventRepository.findByPromotionId(promotion.getPromotionId()).get(0).getCustomerId(), is("c1"));
  }

  /**
   * 前置：顾客 dup 的抢券事件已经在仓库里；库存重新初始化后待处理条数记下来。
   * 动作：同一顾客再次 tryGrab 成功。
   * 期望：两秒内待处理条数回到抢之前或更少，该活动的抢券事件仍然只有 1 条。
   */
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

  /**
   * 前置：活动只剩 1 张券。
   * 动作：顾客 last 把它抢走。
   * 期望：两秒内结束事件恰好出现 1 条，不会重复写。
   */
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
