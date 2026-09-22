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
