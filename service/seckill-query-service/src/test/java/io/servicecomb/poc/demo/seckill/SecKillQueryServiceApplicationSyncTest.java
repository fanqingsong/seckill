/*
 * ┌─ 文件 ─────────────────────────────────────────┐
 * │ SecKillQueryServiceApplicationSyncTest.java   │
 * │ 场景：按券编号增量拉取比给定值更新的券          │
 * └───────────────────────────────────────────────┘
 *
 * 三张券先写入 SecKillStore
 * │
 * ▼
 * 【本文件】MockMvc 依次 GET /sync/{id}
 * │
 * ├── 从 0 拉 ──▶ 三场活动都在
 * └── 用最后一张编号再拉 ──▶ 正文 []
 *
 * 一句话：这是同步接口，页面上的「我的券」不走这里。
 */
package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.servicecomb.poc.demo.QueryServiceApplication;
import io.servicecomb.poc.demo.seckill.dto.CouponInfo;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 守护按券编号增量拉取：{@code GET /sync/{id}} 只返回编号比给定值更新的券。
 * <p>
 * 券先写入 {@link SecKillStore}。这个接口给同步用，页面上的「我的券」走的是 /query，不是这里。
 * 本文件没有出现 Kafka、Elasticsearch 或 H2。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = QueryServiceApplication.class)
@AutoConfigureMockMvc
public class SecKillQueryServiceApplicationSyncTest {
  private static final String customerId = "tester";

  private final PromotionEntity promotion1 = generatePromotion();
  private final PromotionEntity promotion2 = generatePromotion();
  private final PromotionEntity promotion3 = generatePromotion();

  @Autowired
  private SecKillStore store;

  @Autowired
  private MockMvc mockMvc;

  /**
   * 前置：同一顾客在三个活动下各有一张券。
   * 动作：先 GET /sync/0，再按返回编号从小到大，用这三张券的编号各拉一次。
   * 期望：第一次含三个活动；之后依次少掉已经见过的活动；用最后一张的编号再拉时正文是 []。
   */
  @Test
  public void syncCoupon() throws Exception {
    addCouponToCustomer(customerId, promotion1);
    addCouponToCustomer(customerId, promotion2);
    addCouponToCustomer(customerId, promotion3);

    ObjectMapper mapper = new ObjectMapper();
    MvcResult result = mockMvc.perform(get("/sync/0").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string(
            allOf(
                containsString(customerId),
                containsString(promotion1.getPromotionId()),
                containsString(promotion2.getPromotionId()),
                containsString(promotion3.getPromotionId())))).andReturn();
    List<CouponInfo> coupons = Arrays
        .asList(mapper.readValue(result.getResponse().getContentAsString(), CouponInfo[].class));
    coupons.sort(Comparator.comparingInt(CouponInfo::getId));

    mockMvc.perform(get("/sync/" + coupons.get(0).getId()).contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string(
            allOf(
                containsString(customerId),
                not(containsString(promotion1.getPromotionId())),
                containsString(promotion2.getPromotionId()),
                containsString(promotion3.getPromotionId()))));

    mockMvc.perform(get("/sync/" + coupons.get(1).getId()).contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string(
            allOf(
                containsString(customerId),
                not(containsString(promotion1.getPromotionId())),
                not(containsString(promotion2.getPromotionId())),
                containsString(promotion3.getPromotionId()))));

    mockMvc.perform(get("/sync/" + coupons.get(2).getId()).contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string("[]"));
  }

  private void addCouponToCustomer(String customerId, PromotionEntity promotion) {
    store.saveCoupon(new CouponEntity<String>(promotion.getPromotionId(), System.currentTimeMillis(),
        promotion.getDiscount(), customerId));
  }

  private PromotionEntity generatePromotion() {
    return new PromotionEntity(new Date(), new Date(System.currentTimeMillis() + 24 * 3600 * 1000), 1, 0.8f);
  }
}
