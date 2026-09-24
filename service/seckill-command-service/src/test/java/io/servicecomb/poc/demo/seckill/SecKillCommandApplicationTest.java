/*
 * ┌─ 文件 ──────────────────────────────────────┐
 * │ SecKillCommandApplicationTest.java         │
 * │ 场景：顾客提交抢券，或活动编号无效          │
 * └────────────────────────────────────────────┘
 *
 * MockMvc POST /command/coupons/
 * │
 * ▼
 * 【本文件】setUp 用 SecKillStore 放好 10 张库存
 * │
 * ├── 活动有效 ──▶ HTTP 200「Request accepted」
 * └── 未知活动 ──▶ HTTP 400，正文含 Invalid promotion
 *
 * 一句话：成功只表示请求被接受。
 */
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

/**
 * 守护抢券 HTTP：{@code POST /command/coupons/}。
 * <p>
 * {@code setUp} 用 {@link SecKillStore#initStock} 放好 10 张库存，并把命令服务放进活动映射。
 * 成功时响应正文是 Request accepted，只表示请求被接受。本文件没有出现 Kafka、Elasticsearch 或 H2。
 */
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

  /**
   * 前置：该活动库存已初始化。
   * 动作：顾客编号用字符串 zyy 抢券。
   * 期望：HTTP 200，正文是 Request accepted。
   */
  @Test
  public void grabCouponUseStringCustomerIdSuccessfully() throws Exception {
    mockMvc.perform(post("/command/coupons/").contentType(APPLICATION_JSON)
        .content(format.serialize(new CouponDto(promotion.getPromotionId(), "zyy"))))
        .andExpect(status().isOk()).andExpect(content().string("Request accepted"));
  }

  /**
   * 前置：同一活动库存已初始化。
   * 动作：顾客编号用数字 10001 的字符串形式提交。
   * 期望：HTTP 200，正文同样是 Request accepted。
   */
  @Test
  public void grabCouponUseIntCustomerIdSuccessfully() throws Exception {
    mockMvc.perform(post("/command/coupons/").contentType(APPLICATION_JSON)
        .content(format.serialize(new CouponDto(promotion.getPromotionId(), String.valueOf(10001)))))
        .andExpect(status().isOk()).andExpect(content().string("Request accepted"));
  }

  /**
   * 前置：命令服务里只有 setUp 放进去的那一个活动。
   * 动作：活动编号换成随机 UUID 再抢。
   * 期望：HTTP 400，正文含 Invalid promotion。
   */
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
