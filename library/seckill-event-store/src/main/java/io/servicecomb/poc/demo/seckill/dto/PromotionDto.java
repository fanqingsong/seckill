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
 * │ PromotionDto.java                                    │
 * │ 链路：创建活动 · 请求体                              │
 * └──────────────────────────────────────────────────────┘
 *
 *   Admin 的 HTTP 请求体
 *      │
 *      ▼
 *   【本文件】券数、折扣、开始与结束时间
 *      │
 *      ▼
 *   组装 PromotionEntity 写入活动表
 *
 * 一句话：这是请求参数，不是 PostgreSQL 里的活动行。
 */

package io.servicecomb.poc.demo.seckill.dto;

import java.util.Date;

/**
 * Admin 创建或修改活动时，HTTP 请求体里的活动参数。它不是数据库行。
 * <p>
 * Admin 校验通过后，用这些字段 {@code new PromotionEntity} 再保存到 PostgreSQL。
 * 这时 Redis 还没有库存。无参构造器和 setter 是给 JSON 反序列化用的：Jackson 先建空对象，
 * 再按请求字段调用 setter。默认券数为 0、折扣为 1，真正的值以请求为准。
 */
public class PromotionDto {
  /** 券张数。默认 0，Admin 会拒绝不大于 0 的值。 */
  private int numberOfCoupons = 0;
  /** 折扣。默认 1。Admin 要求大于 0 且不超过 1。 */
  private float discount = 1;
  /** 开始时刻。未设置时是 null。 */
  private Date publishTime;
  /** 结束时刻。只传开始时间的构造器会把它设成很远的未来。 */
  private Date finishTime;

  /**
   * JSON 反序列化用的空对象。
   */
  public PromotionDto() {
  }

  /**
   * 不单独指定结束时间。结束时间取 {@code Long.MAX_VALUE} 对应的日期。
   *
   * @param numberOfCoupons 券张数
   * @param discount 折扣
   * @param publishTime 开始时刻
   */
  public PromotionDto(int numberOfCoupons, float discount, Date publishTime) {
    this(numberOfCoupons, discount, publishTime, new Date(Long.MAX_VALUE));
  }

  /**
   * 填满创建活动所需的四个字段。不写数据库。
   *
   * @param numberOfCoupons 券张数
   * @param discount 折扣
   * @param publishTime 开始时刻
   * @param finishTime 结束时刻
   */
  public PromotionDto(int numberOfCoupons, float discount, Date publishTime, Date finishTime) {
    this.numberOfCoupons = numberOfCoupons;
    this.discount = discount;
    this.publishTime = publishTime;
    this.finishTime = finishTime;
  }

  /** 券张数。 */
  public int getNumberOfCoupons() {
    return numberOfCoupons;
  }

  /** 设置券张数。 */
  public void setNumberOfCoupons(int numberOfCoupons) {
    this.numberOfCoupons = numberOfCoupons;
  }

  /** 折扣。 */
  public float getDiscount() {
    return discount;
  }

  /** 设置折扣。 */
  public void setDiscount(float discount) {
    this.discount = discount;
  }

  /** 开始时刻。 */
  public Date getPublishTime() {
    return publishTime;
  }

  /** 设置开始时刻。 */
  public void setPublishTime(Date publishTime) {
    this.publishTime = publishTime;
  }

  /** 结束时刻。 */
  public Date getFinishTime() {
    return finishTime;
  }

  /** 设置结束时刻。 */
  public void setFinishTime(Date finishTime) {
    this.finishTime = finishTime;
  }
}
