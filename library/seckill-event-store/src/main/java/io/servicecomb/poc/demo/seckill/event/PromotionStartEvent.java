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

package io.servicecomb.poc.demo.seckill.event;

import io.servicecomb.poc.demo.seckill.Format;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;

/**
 * 「活动已开始、Redis 库存已初始化」这一事实的内存对象。
 * <p>
 * Command 在 {@code publishTime} 初始化 Redis 之后创建它，再和 outbox 一起追加进 PostgreSQL。
 * Event 服务从 Kafka 读到后，把其中的活动放进 Redis 的进行中列表，并写入 Elasticsearch。
 * 创建本对象不会写任何存储。{@code extends SecKillEvent} 表示它是三种事件之一，
 * 种类名用本类的简单类名 {@code PromotionStartEvent}。
 */
public class PromotionStartEvent extends SecKillEvent {

  /** 已经写在 PostgreSQL 活动表里的那场活动。正文就是它的 JSON。 */
  private final PromotionEntity promotion;

  /**
   * 用活动编号作为事件的 promotionId。不访问 Redis 或数据库。
   *
   * @param promotion 要开始的活动，必须已经有 promotionId
   */
  public PromotionStartEvent(PromotionEntity promotion) {
    super(promotion.getPromotionId(), PromotionStartEvent.class.getSimpleName());
    this.promotion = promotion;
  }

  /** 这场活动的定义，投影时用它恢复 Redis 和搜索索引。 */
  public PromotionEntity getPromotion() {
    return promotion;
  }

  /**
   * 把活动序列化成事件正文。不写库。
   *
   * @param format JSON 工具
   * @return 活动的 JSON 字符串
   */
  @Override
  public String getContent(Format format) {
    return format.serialize(promotion);
  }
}
