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

package io.servicecomb.poc.demo.seckill.dto;

import java.io.Serializable;
import java.util.Date;

/**
 * 查询接口返回给浏览器的一张券。本类自己不访问 Redis 或 Elasticsearch。
 * <p>
 * Query 服务从读模型取出 {@code CouponEntity} 后，转成这个对象再写成 JSON。
 * {@code implements Serializable} 表示对象可以被序列化；{@code serialVersionUID} 是序列化版本号，
 * 改字段时如果版本号不变，旧数据仍按这个结构读。
 * <p>
 * 刚抢成功时，读模型里可能还没有这张券，列表接口也就还不会返回它。这是投影延迟，不是本类漏字段。
 */
public class CouponInfo implements Serializable {
  private static final long serialVersionUID = 1L;

  private int id;

  private String promotionId;

  private Date time;

  private float discount;

  private String customerId;

  /** 给反序列化用的空构造器。JSON 框架需要先 new 再填字段。 */
  public CouponInfo() {
  }

  /**
   * 用读模型里的一张券填满返回字段。
   *
   * @param id 券编号，同步接口用它当作「已经看到的最后一张」
   * @param customerId 抢到这张券的顾客
   * @param promotionId 活动编号
   * @param time 抢到的时间
   * @param discount 折扣，和活动上的折扣一致
   */
  public CouponInfo(int id, String customerId, String promotionId, Date time, float discount) {
    this.id = id;
    this.customerId = customerId;
    this.promotionId = promotionId;
    this.time = time;
    this.discount = discount;
  }

  /** 券编号。 */
  public int getId() {
    return id;
  }

  /** 顾客编号。 */
  public String getCustomerId() {
    return customerId;
  }

  /** 活动编号。 */
  public String getPromotionId() {
    return promotionId;
  }

  /** 抢到的时间。 */
  public Date getTime() {
    return time;
  }

  /** 折扣。 */
  public float getDiscount() {
    return discount;
  }
}
