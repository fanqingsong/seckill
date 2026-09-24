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
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ CouponDto.java                                       │
 * │ 链路：抢券 · 请求体                                  │
 * └──────────────────────────────────────────────────────┘
 *
 *   浏览器 POST /command/coupons/
 *      │
 *      ▼
 *   【本文件】promotionId 与 customerId
 *      │
 *      ▼
 *   Command 校验后，再走 Redis Lua
 *
 * 一句话：只装请求字段；HTTP 成功时事件表通常还没有这张券。
 */

package io.servicecomb.poc.demo.seckill.dto;

/**
 * 抢券请求 {@code POST /command/coupons/} 的 JSON 体：哪场活动、哪位顾客。
 * <p>
 * Command 用它做校验，再走 Redis Lua 扣库存。校验通过并返回成功时，PostgreSQL 里通常还没有
 * {@code CouponGrabbedEvent}，那一行由 Persist 稍后写入。无参构造器和 setter 给 JSON 反序列化用。
 * 本类不写 Redis 或数据库。
 */
public class CouponDto {

  /** 要抢的活动编号。 */
  private String promotionId;

  /** 顾客编号。同一活动内重复提交应得到「已经抢过」，而不是再扣一次库存。 */
  private String customerId;

  /** 活动编号。 */
  public String getPromotionId() {
    return promotionId;
  }

  /** 设置活动编号。 */
  public void setPromotionId(String promotionId) {
    this.promotionId = promotionId;
  }

  /** 顾客编号。 */
  public String getCustomerId() {
    return customerId;
  }

  /** 设置顾客编号。 */
  public void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  /**
   * JSON 反序列化用的空对象。
   */
  public CouponDto() {
  }

  /**
   * 直接填上活动和顾客。不访问 Redis。
   *
   * @param promotionId 活动编号
   * @param customerId 顾客编号
   */
  public CouponDto(String promotionId, String customerId) {
    this.promotionId = promotionId;
    this.customerId = customerId;
  }
}