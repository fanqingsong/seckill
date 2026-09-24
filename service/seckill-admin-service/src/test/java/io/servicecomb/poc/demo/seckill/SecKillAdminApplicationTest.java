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
 * ┌─ 文件 ─────────────────────────────────────┐
 * │ SecKillAdminApplicationTest.java          │
 * │ 场景：创建活动、修改活动，以及非法参数      │
 * └───────────────────────────────────────────┘
 *
 * MockMvc POST / PUT /admin/promotions/
 * │
 * ▼
 * 【本文件】经 SpringPromotionRepository 落库再读回
 * │
 * ├── 合法 ──▶ HTTP 200，仓库字段与提交一致
 * └── 券数为 0、折扣为负、活动不存在 ──▶ HTTP 400
 *
 * 一句话：只测 Admin 创建与修改，这条请求还不初始化库存。
 */
package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.UUID;

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

import io.servicecomb.poc.demo.AdminServiceApplication;
import io.servicecomb.poc.demo.seckill.dto.PromotionDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringPromotionRepository;
import io.servicecomb.poc.demo.seckill.web.SecKillAdminRestController;

/**
 * 守护「创建活动」这条链路：浏览器最终会打到 Admin 的 {@code /admin/promotions/}。
 * <p>
 * {@code @RunWith(SpringRunner.class)} 让 JUnit 4 把本类交给 Spring 启动；
 * {@code @SpringBootTest} 只装配 Admin 应用，{@code @WebAppConfiguration} 表示按 Web 环境测控制器。
 * 活动经 {@link SpringPromotionRepository} 落库。本文件没有出现 Redis、Kafka、Elasticsearch 或 H2。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = AdminServiceApplication.class)
@WebAppConfiguration
public class SecKillAdminApplicationTest {

  private final Format format = new JacksonGeneralFormat();

  private MockMvc mockMvc;

  @Autowired
  private SecKillAdminRestController controller;

  @Autowired
  private SpringPromotionRepository repository;


  @Before
  public void setup() throws Exception {

    mockMvc = MockMvcBuilders.standaloneSetup(controller).setHandlerExceptionResolvers(withExceptionControllerAdvice())
        .build();

    repository.deleteAll();
  }

  /**
   * 前置：{@code setup} 已清空活动仓库。
   * 动作：POST 券数量 5、折扣 0.7、开始时间在未来的活动。
   * 期望：HTTP 200，响应体能解析成活动编号，仓库里有一条记录。
   */
  @Test
  public void createsPromotionSuccessfully() throws Exception {
    MvcResult result = mockMvc.perform(post("/admin/promotions/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(5, 0.7f, timeFromNow(2000)))))
        .andExpect(status().isOk()).andReturn();

    UUID.fromString(result.getResponse().getContentAsString());

    assertThat(repository.count(), is(1L));
  }

  /**
   * 前置：仓库是空的。
   * 动作：券数量写成 0 再创建。
   * 期望：HTTP 400，正文里能看到 Invalid promotion。
   */
  @Test
  public void failsWhenNumberOfCouponsIsInvalid() throws Exception {
    mockMvc.perform(post("/admin/promotions/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(0, 0.7f, timeFromNow(2000)))))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Invalid promotion {numberOfCoupons=")));
  }

  /**
   * 前置：仓库是空的。
   * 动作：折扣写成负数再创建。
   * 期望：HTTP 400，正文里能看到 Invalid promotion。
   */
  @Test
  public void failsWhenDiscountIsInvalid() throws Exception {
    mockMvc.perform(post("/admin/promotions/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(5, -0.1f, timeFromNow(2000)))))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Invalid promotion {numberOfCoupons=")));
  }

  /**
   * 前置：先创建一条尚未改过的活动。
   * 动作：PUT 新的券数量、折扣、开始时间和结束时间。
   * 期望：HTTP 200，仓库里读回的四个字段与提交一致。
   */
  @Test
  public void updatePromotionSuccessfully() throws Exception {
    MvcResult result = mockMvc.perform(post("/admin/promotions/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(5, 0.7f, timeFromNow(2000)))))
        .andExpect(status().isOk()).andReturn();

    String promotionId = result.getResponse().getContentAsString();
    int numberOfCoupons = 10;
    float discount = 0.8f;
    Date publishTime = truncateToSeconds(new Date());
    Date finishTime = truncateToSeconds(new Date(System.currentTimeMillis() + 300000));

    mockMvc.perform(put("/admin/promotions/" + promotionId + "/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(numberOfCoupons, discount, publishTime, finishTime))))
        .andExpect(status().isOk());

    PromotionEntity promotion = repository.findTopByPromotionId(promotionId);
    assertThat(promotion.getDiscount(), is(discount));
    assertThat(promotion.getNumberOfCoupons(), is(numberOfCoupons));
    assertThat(promotion.getPublishTime().getTime(), is(publishTime.getTime()));
    assertThat(promotion.getFinishTime().getTime(), is(finishTime.getTime()));
  }

  /**
   * 前置：没有先创建活动。
   * 动作：对一个随机活动编号发 PUT。
   * 期望：HTTP 400，正文含 PromotionEntity not exists。
   */
  @Test
  public void failsUpdatePromotionWhenPromotionDoesNotExist() throws Exception {
    mockMvc.perform(put("/admin/promotions/" + UUID.randomUUID().toString() + "/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(5, 0.7f, timeFromNow(2000)))))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("PromotionEntity not exists")));
  }

  /**
   * 前置：已经有一条合法活动。
   * 动作：PUT 时把券数量写成 0、折扣写成负数。
   * 期望：HTTP 400，正文含 Invalid promotion。
   */
  @Test
  public void failsUpdatePromotionWhenDtoIsInvalid() throws Exception {
    MvcResult result = mockMvc.perform(post("/admin/promotions/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(5, 0.7f, timeFromNow(2000)))))
        .andExpect(status().isOk()).andReturn();

    String promotionId = result.getResponse().getContentAsString();
    int numberOfCoupons = 0;
    float discount = -0.8f;
    Date publishTime = truncateToSeconds(new Date());
    Date finishTime = truncateToSeconds(new Date(System.currentTimeMillis() + 300000));

    mockMvc.perform(put("/admin/promotions/" + promotionId + "/").contentType(APPLICATION_JSON)
        .content(format.serialize(new PromotionDto(numberOfCoupons, discount, publishTime, finishTime))))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Invalid promotion {numberOfCoupons=")));
  }


  private Date timeFromNow(int offset) {
    return new Date(System.currentTimeMillis() + offset);
  }

  private Date truncateToSeconds(Date date) {
    return new Date((date.getTime() / 1000) * 1000);
  }

  private ExceptionHandlerExceptionResolver withExceptionControllerAdvice() {
    final ExceptionHandlerExceptionResolver exceptionResolver = new InvocationExceptionHandlerExceptionResolver();
    exceptionResolver.afterPropertiesSet();
    return exceptionResolver;
  }
}
