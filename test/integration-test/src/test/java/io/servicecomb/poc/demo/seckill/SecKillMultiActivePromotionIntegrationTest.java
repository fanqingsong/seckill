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

package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.CoreMatchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.servicecomb.poc.demo.seckill.dto.CouponDto;
import io.servicecomb.poc.demo.seckill.dto.PromotionDto;
import io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringCouponRepository;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;

/**
 * 守护同时有两场进行中的活动：同一顾客可以各抢一张，查询结果里两场都在。
 * <p>
 * 请求打在 {@link IntegrationTestApplication} 提供的 MockMvc 上。
 * 本文件没有出现 Redis、Kafka、Elasticsearch 或 H2 的类型或配置。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = IntegrationTestApplication.class)
@WebAppConfiguration
@AutoConfigureMockMvc
public class SecKillMultiActivePromotionIntegrationTest {

  private final Format format = new JacksonGeneralFormat();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private SpringPromotionRepository promotionRepository;

  @Autowired
  private SpringSecKillEventRepository eventRepository;

  @Autowired
  private SpringCouponRepository couponRepository;

  /**
   * 前置：没有先清空仓库，两场活动的开始时间都是现在。
   * 动作：创建 5 张和 10 张券的两场活动，等待 1 秒后顾客 zyy 各抢一次，再查该顾客的券。
   * 期望：两次抢券都是 HTTP 200 且正文为 Request accepted；查询正文同时含两个活动编号和 zyy。
   */
  @Test
  public void createAndPublishMultiPromotionAndGrabSuccessfully() throws Exception {
    MvcResult result = mockMvc.perform(post("/admin/promotions/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(5, 0.7f, new Date()))))
        .andExpect(status().isOk()).andReturn();
    String promotionId1 = result.getResponse().getContentAsString();

    result = mockMvc.perform(post("/admin/promotions/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(10, 0.8f, new Date()))))
        .andExpect(status().isOk()).andReturn();
    String promotionId2 = result.getResponse().getContentAsString();

    Thread.sleep(1000);

    mockMvc.perform(post("/command/coupons/").contentType(APPLICATION_JSON)
        .content(format.serialize(new CouponDto(promotionId1, "zyy"))))
        .andExpect(status().isOk()).andExpect(content().string("Request accepted"));

    mockMvc.perform(post("/command/coupons/").contentType(APPLICATION_JSON)
        .content(format.serialize(new CouponDto(promotionId2, "zyy"))))
        .andExpect(status().isOk()).andExpect(content().string("Request accepted"));

    Thread.sleep(1000);

    mockMvc.perform(get("/query/coupons/{customerId}", "zyy").contentType(APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(promotionId1)))
        .andExpect(content().string(containsString(promotionId2)))
        .andExpect(content().string(containsString("zyy")));
  }
}
