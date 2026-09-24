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
 * │ PromotionEntity.java                                 │
 * │ 链路：创建活动 · 活动表                              │
 * └──────────────────────────────────────────────────────┘
 *
 *   Admin 创建或修改活动
 *      │
 *      ▼
 *   【本文件】券数、折扣、投放与结束时间
 *      │
 *      ▼
 *   PostgreSQL 活动表（尚无库存）
 *
 * 一句话：这是可修改的活动定义，不是追加进去的事件。
 */

package io.servicecomb.poc.demo.seckill.entities;

import java.util.Date;
import java.util.UUID;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 一场秒杀活动在 PostgreSQL 里的当前定义：什么时候开始、什么时候结束、多少张券、几折。
 * <p>
 * Admin 服务创建或修改活动时 {@code save} 本类。此时 Redis 里还没有库存。
 * Command 要等到 {@link #publishTime} 才初始化 Redis，并追加 {@code PromotionStartEvent}
 * （正文里会再序列化一份本对象）。Event 服务投影后，进行中的活动会出现在 Redis 和
 * Elasticsearch。本类不是追加事件，同一活动之后可以用 setter 改字段再保存。
 * <p>
 * {@code @Entity} 表示 JPA 管理它，默认表名是类名。{@code promotionId} 上有索引，
 * Persist 和 Admin 按业务编号查这一行时不用扫全表。{@code @Id} 和
 * {@code GenerationType.IDENTITY} 表示 {@link #id} 由数据库自增，和业务编号
 * {@link #promotionId} 不是一回事。
 */
@Entity
@Table(indexes = {
    @Index(name = "idx_promotion_business_id", columnList = "promotionId")
})
public class PromotionEntity {

  /** 数据库自增主键。对外使用的是 {@link #promotionId}。 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private int id;

  /** 活动的业务编号，创建时生成，修改活动时保持不变。 */
  private String promotionId;
  /** 到达这一刻之后，Command 才初始化 Redis 库存并可能写入开始事件。 */
  private Date publishTime;
  /** 活动结束时刻。短构造器会把它设成很远的未来，表示调用方没有另给结束时间。 */
  private Date finishTime;
  /** 可抢的券数量。真正扣减发生在 Redis，不在本字段上减 1。 */
  private int numberOfCoupons;
  /** 折扣，合法范围由 Admin 校验，本类不检查。 */
  private float discount;

  /** 自增主键。 */
  public int getId() {
    return id;
  }

  /** 活动业务编号。 */
  public String getPromotionId() {
    return promotionId;
  }

  /** 设置活动业务编号。 */
  public void setPromotionId(String promotionId) {
    this.promotionId = promotionId;
  }

  /** 开始投放的时刻。 */
  public Date getPublishTime() {
    return publishTime;
  }

  /** 设置开始投放的时刻。 */
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

  /** 券的总张数。 */
  public int getNumberOfCoupons() {
    return numberOfCoupons;
  }

  /** 设置券的总张数。 */
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

  /**
   * JPA 需要的无参构造器。创建活动用下面两个构造器，它们会生成新的 {@code promotionId}。
   */
  public PromotionEntity() {
  }

  /**
   * 只给出开始时间时使用。结束时间取 {@code new Date(Long.MAX_VALUE)}，即 Java 能表示的最远日期。
   * 不写数据库。
   *
   * @param publishTime 开始时刻
   * @param numberOfCoupons 券张数
   * @param discount 折扣
   */
  public PromotionEntity(Date publishTime, int numberOfCoupons, float discount) {
    this(publishTime, new Date(Long.MAX_VALUE), numberOfCoupons, discount);
  }

  /**
   * 创建一场新活动并生成 {@code promotionId}。只填内存字段，Admin 再 {@code save} 到 PostgreSQL。
   * 不初始化 Redis。
   *
   * @param publishTime 开始时刻
   * @param finishTime 结束时刻
   * @param numberOfCoupons 券张数
   * @param discount 折扣
   */
  public PromotionEntity(Date publishTime, Date finishTime, int numberOfCoupons, float discount) {
    this.promotionId = UUID.randomUUID().toString();
    this.publishTime = publishTime;
    this.finishTime = finishTime;
    this.numberOfCoupons = numberOfCoupons;
    this.discount = discount;
  }

  /**
   * 打成一行文字，便于日志查看活动编号和时间。不访问数据库。
   *
   * @return 包含编号、起止时间和券信息的字符串
   */
  @Override
  public String toString() {
    return "PromotionEntity{" +
        "promotionId='" + promotionId + '\'' +
        ", publishTime=" + publishTime +
        ", finishTime=" + finishTime +
        ", numberOfCoupons=" + numberOfCoupons +
        ", discount=" + discount +
        '}';
  }
}