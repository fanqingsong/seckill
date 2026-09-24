package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.servicecomb.poc.demo.QueryServiceApplication;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import java.util.Date;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 守护查询页会用到的两个读接口：进行中的活动，以及某位顾客的券。
 * <p>
 * 数据直接写进 {@link SecKillStore}，再由 MockMvc 请求 {@code /query/promotions} 和 {@code /query/coupons/}。
 * {@code @AutoConfigureMockMvc} 会准备好发 HTTP 的 {@link MockMvc}，并不真的打开端口。
 * 本文件没有搜索请求，也没有出现 Kafka、Elasticsearch 或 H2。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = QueryServiceApplication.class)
@AutoConfigureMockMvc
public class SecKillQueryServiceApplicationTest {

  private static final String customerId = "tester";

  private final PromotionEntity promotion1 = generatePromotion();
  private final PromotionEntity promotion2 = generatePromotion();
  private final PromotionEntity promotion3 = generatePromotion();

  @Autowired
  private SecKillStore store;

  @Autowired
  private MockMvc mockMvc;

  /**
   * 前置：没有给顾客 unknown 写过券。
   * 动作：GET /query/coupons/unknown。
   * 期望：HTTP 200，正文是空数组 []。
   */
  @Test
  public void queryCouponWithNonExistentCustomer() throws Exception {
    mockMvc.perform(get("/query/coupons/{customerId}", "unknown").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string("[]"));
  }

  /**
   * 前置：只把 promotion1 和 promotion3 放进进行中的活动，promotion2 没有放入。
   * 动作：GET /query/promotions。
   * 期望：HTTP 200，正文含前两个活动编号，不含 promotion2。
   */
  @Test
  public void activePromotionCanBeQueried() throws Exception {
    store.saveActivePromotion(promotion1);
    store.saveActivePromotion(promotion3);

    mockMvc.perform(get("/query/promotions").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string(
            allOf(
                containsString(promotion1.getPromotionId()),
                containsString(promotion3.getPromotionId()),
                not(containsString(promotion2.getPromotionId())))));
  }

  /**
   * 前置：tester 有 promotion1 的券，顾客 unknown 有 promotion2 的券。
   * 动作：查询 tester，再给 tester 加上 promotion3 的券后查第二次。
   * 期望：两次都是 HTTP 200；第一次只含 promotion1 和 tester，第二次同时含 promotion1 与 promotion3，始终不含别人的券。
   */
  @Test
  public void grabbedCouponsCanBeQueried() throws Exception {
    addCouponToCustomer(customerId, promotion1);
    addCouponToCustomer("unknown", promotion2);

    mockMvc.perform(get("/query/coupons/{customerId}", customerId).contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string(
            allOf(
                containsString(promotion1.getPromotionId()),
                containsString(customerId),
                not(containsString(promotion2.getPromotionId())),
                not(containsString("unknown")))));

    addCouponToCustomer(customerId, promotion3);

    mockMvc.perform(get("/query/coupons/{customerId}", customerId).contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string(
            allOf(
                containsString(promotion1.getPromotionId()),
                containsString(promotion3.getPromotionId()),
                containsString(customerId),
                not(containsString(promotion2.getPromotionId())),
                not(containsString("unknown")))));
  }

  private void addCouponToCustomer(String customerId, PromotionEntity promotion) {
    store.saveCoupon(new CouponEntity<String>(promotion.getPromotionId(), System.currentTimeMillis(),
        promotion.getDiscount(), customerId));
  }

  private PromotionEntity generatePromotion() {
    return new PromotionEntity(new Date(), new Date(System.currentTimeMillis() + 24 * 3600 * 1000), 1, 0.8f);
  }
}
