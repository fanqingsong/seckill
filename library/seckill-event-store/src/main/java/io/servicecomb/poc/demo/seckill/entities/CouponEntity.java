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
 * │ CouponEntity.java                                    │
 * │ 链路：抢到的券                                       │
 * └──────────────────────────────────────────────────────┘
 *
 *   Persist 放进抢券事件正文
 *      │
 *      ▼
 *   【本文件】折扣、顾客、抢到时间
 *      │
 *      ▼
 *   事件 content，随后投影到 Redis / Elasticsearch
 *
 * 一句话：券是事件正文和读模型对象，不是 PostgreSQL 券表。
 */

package io.servicecomb.poc.demo.seckill.entities;

/**
 * 一张已经抢到的券。它同时是 {@code CouponGrabbedEvent} 的正文，以及查询侧的读模型对象。
 * <p>
 * Persist 服务把本对象序列化进事件表的 {@code content}，并写入 outbox。Event 服务消费
 * Kafka 后，把券放进 Redis（查询页读这里），并索引到 Elasticsearch（搜索才读）。
 * 抢券 HTTP 线程不创建数据库里的券行。本类不是 JPA 实体：PostgreSQL 不维护一张券表。
 * 类名后的 {@code <T>} 是泛型：顾客编号的具体类型由调用方决定；现有查询链路使用 {@code String}。
 *
 * @param <T> 顾客编号的类型
 */
public class CouponEntity<T> {

  /** 券的编号。写入 Redis 读模型时由存储实现赋值，不是数据库自增主键。 */
  private int id;

  /** 这张券属于哪一场活动。 */
  private String promotionId;

  /** 抢到的毫秒时间戳。 */
  private long time;

  /** 折扣，与活动上的折扣一致，例如 0.7 表示七折。 */
  private float discount;

  /** 抢到这张券的顾客。 */
  private T customerId;

  /**
   * JSON 反序列化用的无参构造器。业务代码构造一张新券时用下面的全参构造器。
   */
  public CouponEntity() {
  }

  /**
   * 组装一张券，此时还不写 PostgreSQL、Redis 或 Elasticsearch。
   *
   * @param promotionId 活动编号
   * @param time 抢到的毫秒时间
   * @param discount 折扣
   * @param customerId 顾客编号
   */
  public CouponEntity(String promotionId, long time, float discount, T customerId) {
    this.promotionId = promotionId;
    this.time = time;
    this.discount = discount;
    this.customerId = customerId;
  }

  /** 券编号。 */
  public int getId() {
    return id;
  }

  /** 回填券编号。Redis 投影会在保存前设置它。 */
  public void setId(int id) {
    this.id = id;
  }

  /** 活动编号。 */
  public String getPromotionId() {
    return promotionId;
  }

  /** 设置活动编号。 */
  public void setPromotionId(String promotionId) {
    this.promotionId = promotionId;
  }

  /** 顾客编号。 */
  public T getCustomerId() {
    return customerId;
  }

  /** 设置顾客编号。 */
  public void setCustomerId(T customerId) {
    this.customerId = customerId;
  }

  /** 折扣。 */
  public float getDiscount() {
    return discount;
  }

  /** 抢到的毫秒时间。 */
  public long getTime() {
    return time;
  }
}
