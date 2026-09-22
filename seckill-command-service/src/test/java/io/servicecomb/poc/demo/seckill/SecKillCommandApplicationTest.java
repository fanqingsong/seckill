package io.servicecomb.poc.demo.seckill;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.CoreMatchers.containsString;

import io.servicecomb.poc.demo.CommandServiceApplication;
import io.servicecomb.poc.demo.seckill.dto.CouponDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.web.SecKillCommandRestController;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = CommandServiceApplication.class)
@WebAppConfiguration
public class SecKillCommandApplicationTest {

  private final Format format = new JacksonGeneralFormat();
  private final PromotionEntity promotion = new PromotionEntity(new Date(), 10, 0.7f);

  private MockMvc mockMvc;

  @Autowired
  private SecKillCommandRestController controller;

  @Autowired
  private Map<String, SecKillCommandService<String>> commandServices;

  @Autowired
  private SecKillStore store;

  @Autowired
  private TransactionalEventOutboxWriter writer;

  @Autowired
  private io.servicecomb.poc.demo.seckill.event.SecKillEventFormat eventFormat;

  @Before
  public void setUp() throws Exception {
    mockMvc = MockMvcBuilders.standaloneSetup(controller).setHandlerExceptionResolvers(withExceptionControllerAdvice())
        .build();
    commandServices.clear();
    store.initStock(promotion.getPromotionId(), 10, Collections.<String>emptySet(), 0);
    commandServices.put(promotion.getPromotionId(),
        new SecKillCommandService<String>(promotion, store, writer, eventFormat, false));
  }

  @Test
  public void grabCouponUseStringCustomerIdSuccessfully() throws Exception {
    mockMvc.perform(post("/command/coupons/").contentType(APPLICATION_JSON)
        .content(format.serialize(new CouponDto(promotion.getPromotionId(), "zyy"))))
        .andExpect(status().isOk()).andExpect(content().string("Request accepted"));
  }

  @Test
  public void grabCouponUseIntCustomerIdSuccessfully() throws Exception {
    mockMvc.perform(post("/command/coupons/").contentType(APPLICATION_JSON)
        .content(format.serialize(new CouponDto(promotion.getPromotionId(), String.valueOf(10001)))))
        .andExpect(status().isOk()).andExpect(content().string("Request accepted"));
  }

  @Test
  public void failsGrabCouponWhenCustomerIdIsInvalid() throws Exception {
    mockMvc.perform(post("/command/coupons/").contentType(APPLICATION_JSON)
        .content(format.serialize(new CouponDto(UUID.randomUUID().toString(), "zyy"))))
        .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Invalid promotion")));
  }

  private ExceptionHandlerExceptionResolver withExceptionControllerAdvice() {
    final ExceptionHandlerExceptionResolver exceptionResolver = new InvocationExceptionHandlerExceptionResolver();
    exceptionResolver.afterPropertiesSet();
    return exceptionResolver;
  }
}
