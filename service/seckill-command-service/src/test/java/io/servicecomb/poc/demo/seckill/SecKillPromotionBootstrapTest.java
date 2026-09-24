/*
 *   Copyright 2017 Huawei Technologies Co., Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ SecKillPromotionBootstrapTest.java           │
 * │ 场景：没到开始时间不能抢，到点之后可以抢      │
 * └──────────────────────────────────────────────┘
 *
 * 活动写入 SpringPromotionRepository
 * │
 * ▼
 * 【本文件】立刻 POST /command/coupons/，睡过开始时间再抢
 * │
 * ├── 未到点 ──▶ HTTP 400，正文含 Invalid promotion
 * └── 到点后 ──▶ HTTP 200「Request accepted」
 *
 * 一句话：定时任务到 publishTime 才把活动放进可抢列表。
 */
package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.CoreMatchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;

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

import io.servicecomb.poc.demo.CommandServiceApplication;
import io.servicecomb.poc.demo.seckill.dto.CouponDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import io.servicecomb.poc.demo.seckill.web.SecKillCommandRestController;

/**
 * 守护「没到开始时间不能抢，到点之后可以抢」。
 * <p>
 * 活动先写入 {@link SpringPromotionRepository}。Command 里的定时任务要等到 publishTime
 * 才把这个活动放进可抢列表。本文件没有出现 Redis、Kafka、Elasticsearch 或 H2。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = CommandServiceApplication.class)
@WebAppConfiguration
public class SecKillPromotionBootstrapTest {

  private final Format format = new JacksonGeneralFormat();

  private MockMvc mockMvc;

  @Autowired
  private SecKillCommandRestController controller;

  @Autowired
  private SpringPromotionRepository promotionRepository;

  @Before
  public void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(controller).setHandlerExceptionResolvers(withExceptionControllerAdvice())
        .build();

    promotionRepository.deleteAll();
  }

  /**
   * 前置：活动开始时间大约在 1 秒之后，仓库里已有这条活动。
   * 动作：立刻抢一次，睡过开始时间后再抢一次。
   * 期望：第一次 HTTP 400 且正文含 Invalid promotion；第二次 HTTP 200，正文是 Request accepted。
   */
  @Test
  public void testPromotionStartedWhenPublishTimeReach() throws Exception {
    int waitTime = 1000;

    PromotionEntity delayPromotion = new PromotionEntity(new Date(System.currentTimeMillis() + waitTime), 5, 0.8f);
    promotionRepository.save(delayPromotion);

    mockMvc.perform(post("/command/coupons/").contentType(APPLICATION_JSON)
        .content(format.serialize(new CouponDto(delayPromotion.getPromotionId(), "zyy"))))
        .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Invalid promotion")));

    Thread.sleep(waitTime + 1000);

    mockMvc.perform(post("/command/coupons/").contentType(APPLICATION_JSON)
        .content(format.serialize(new CouponDto(delayPromotion.getPromotionId(), "zyy"))))
        .andExpect(status().isOk()).andExpect(content().string("Request accepted"));
  }

  private ExceptionHandlerExceptionResolver withExceptionControllerAdvice() {
    final ExceptionHandlerExceptionResolver exceptionResolver = new InvocationExceptionHandlerExceptionResolver();
    exceptionResolver.afterPropertiesSet();
    return exceptionResolver;
  }
}
