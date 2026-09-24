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
 * │ SecKillStartedPromotionTest.java             │
 * │ 场景：活动已经开始就不能再改                  │
 * └──────────────────────────────────────────────┘
 *
 * POST /admin/promotions/ 创建开始时间是现在的活动
 * │
 * ▼
 * 【本文件】等它开始后 PUT 同一活动编号
 * │
 * └──▶ HTTP 400，正文含 had started and changes is rejected
 *
 * 一句话：只装配 Admin 控制器的 MockMvc。
 */
package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.CoreMatchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

import io.servicecomb.poc.demo.seckill.dto.PromotionDto;
import io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat;
import io.servicecomb.poc.demo.seckill.web.SecKillAdminRestController;

/**
 * 守护「活动已经开始就不能再改」：创建时开始时间就是现在，等它开始后再 PUT 应被拒绝。
 * <p>
 * 只装配了 Admin 控制器的独立 MockMvc。本文件没有出现 Redis、Kafka、Elasticsearch 或 H2。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = IntegrationTestApplication.class)
@WebAppConfiguration
public class SecKillStartedPromotionTest {
  private final Format format = new JacksonGeneralFormat();

  private MockMvc mockMvc;

  @Autowired
  private SecKillAdminRestController adminRestController;

  @Before
  public void setUp() throws Exception {
    mockMvc = MockMvcBuilders.standaloneSetup(adminRestController)
        .setHandlerExceptionResolvers(withExceptionControllerAdvice())
        .build();
  }

  /**
   * 前置：创建一条开始时间是现在的活动，并等待约 1 秒让它进入已开始。
   * 动作：用同一活动编号 PUT 一份新的活动内容。
   * 期望：HTTP 400，正文含 PromotionEntity had started and changes is rejected。
   */
  @Test
  public void failsUpdatePromotionWhenPromotionHadStarted() throws Exception {
    MvcResult result = mockMvc.perform(post("/admin/promotions/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(5, 0.7f, new Date()))))
        .andExpect(status().isOk()).andReturn();

    Thread.sleep(1000);

    String promotionId = result.getResponse().getContentAsString();

    mockMvc.perform(put("/admin/promotions/" + promotionId + "/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(5, 0.7f, new Date()))))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("PromotionEntity had started and changes is rejected")));
  }

  private ExceptionHandlerExceptionResolver withExceptionControllerAdvice() {
    final ExceptionHandlerExceptionResolver exceptionResolver = new InvocationExceptionHandlerExceptionResolver();
    exceptionResolver.afterPropertiesSet();
    return exceptionResolver;
  }
}
