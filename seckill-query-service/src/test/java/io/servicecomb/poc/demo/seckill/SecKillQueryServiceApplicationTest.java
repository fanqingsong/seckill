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

  @Test
  public void queryCouponWithNonExistentCustomer() throws Exception {
    mockMvc.perform(get("/query/coupons/{customerId}", "unknown").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string("[]"));
  }

  @Test
  public void activePromotionCanBeQueried() throws Exception {
    store.saveActivePromotion(promotion1);
    store.saveActivePromotion(promotion3);

    mockMvc.perform(get("/query/promotions/").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string(
            allOf(
                containsString(promotion1.getPromotionId()),
                containsString(promotion3.getPromotionId()),
                not(containsString(promotion2.getPromotionId())))));
  }

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
