/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ SecKillEventServiceApplicationTest.java      │
 * │ 场景：开始后活动可查，结束后消失，抢券后能读券 │
 * └──────────────────────────────────────────────┘
 *
 * 直接调用 EventProjector.project，不经过 HTTP
 * │
 * ▼
 * 【本文件】把开始、结束、抢券事件投影进 SecKillStore
 * │
 * ├── 两条开始 ──▶ 进行中列表有 2 个活动
 * ├── 一条结束 ──▶ 该活动从列表消失
 * └── 抢券 ──▶ 按顾客能读到 1 张券
 *
 * 一句话：断言的是读模型，不走浏览器。
 */
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

/**
 * 守护 Event 服务把事件投影进读模型：活动开始后出现在进行中列表，结束后消失，抢券后能按顾客读到券。
 * <p>
 * {@code @SpringBootTest} 启动 Event 应用，测试直接调用 {@link EventProjector#project}，不经过 HTTP。
 * 本文件没有出现 Kafka、Elasticsearch 或 H2 的类型或配置。
 */
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

  /**
   * 前置：两个活动都还没投影。
   * 动作：各投影一条开始事件，再给 promotion1 投影结束事件。
   * 期望：开始后进行中的活动有 2 个；结束后只剩下 promotion2。
   */
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

  /**
   * 前置：顾客 zyy 的读模型还是空的。
   * 动作：投影一条该顾客抢到 promotion1 的事件。
   * 期望：按 zyy 能读到 1 张券，顾客编号是 zyy。
   */
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
