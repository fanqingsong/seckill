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
 * │ CouponGrabbedEvent.java                              │
 * │ 链路：抢券 · 落库事件                                │
 * └──────────────────────────────────────────────────────┘
 *
 *   Persist 取出抢券令牌之后
 *      │
 *      ▼
 *   【本文件】某顾客抢到的一张券
 *      │
 *      ▼
 *   PostgreSQL 事件表，并写入 outbox
 *
 * 一句话：抢券 HTTP 不插入本事件，由 Persist 追加。
 */

package io.servicecomb.poc.demo.seckill.event;

import io.servicecomb.poc.demo.seckill.Format;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;

/**
 * 「某位顾客抢到一张券」的内存事件。正文是 {@link CouponEntity}，不是剩余库存数字。
 * <p>
 * Persist 服务从 Redis 抢券队列取出令牌后创建它，再和 outbox 一起写入 PostgreSQL。
 * 抢券 HTTP 成功只表示 Redis 已扣减，那时本事件通常还没插入。Event 服务消费 Kafka 后，
 * 把券放进 Redis 供查询，并索引到 Elasticsearch。类名后的 {@code <T>} 是顾客编号的类型。
 *
 * @param <T> 顾客编号的类型，现有链路使用 {@code String}
 */
public class CouponGrabbedEvent<T> extends SecKillEvent {

  /** 抢到的那张券。事件表的 content 存的就是它。 */
  private final CouponEntity<T> coupon;

  /**
   * 直接包上一张已经组装好的券。不写数据库。
   *
   * @param coupon 券，其中的 promotionId 会成为事件的活动编号
   */
  public CouponGrabbedEvent(CouponEntity<T> coupon) {
    super(coupon.getPromotionId(), CouponGrabbedEvent.class.getSimpleName());
    this.coupon = coupon;
  }

  /**
   * 按活动和顾客现做一张券：时间取当前毫秒，折扣取活动上的折扣。
   * 仍只存在内存里。{@code this(...)} 是调用本类的另一个构造器。
   *
   * @param promotion 券所在的活动
   * @param customerId 抢到券的顾客
   */
  public CouponGrabbedEvent(PromotionEntity promotion, T customerId) {
    this(new CouponEntity<>(
        promotion.getPromotionId(),
        System.currentTimeMillis(),
        promotion.getDiscount(),
        customerId));
  }

  /** 这张券，投影到 Redis 和 Elasticsearch 时读取它。 */
  public CouponEntity<T> getCoupon() {
    return coupon;
  }

  /**
   * 把券序列化成事件正文。
   *
   * @param format JSON 工具
   * @return 券的 JSON
   */
  @Override
  public String getContent(Format format) {
    return format.serialize(coupon);
  }
}
