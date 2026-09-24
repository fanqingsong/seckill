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
 * 「这场活动已经结束」的内存事件。卖完，或到了结束时间且抢券队列已空，都会用它。
 * <p>
 * Command 在到点且队列为空时写入；Persist 在卖完时也可能写入。两者都是追加 PostgreSQL
 * 事件并插入 outbox，不在本类里发 Kafka。Event 服务投影后，会从 Redis 的进行中活动里去掉它，
 * 并在 Elasticsearch 标成结束。{@code extends} 表示继承 {@link SecKillEvent}，种类名是
 * {@code PromotionFinishEvent}。本类没有单独的 getter，读取活动要先反序列化正文。
 */
public class PromotionFinishEvent extends SecKillEvent {

  /** 结束的那场活动，序列化后放进事件正文。 */
  private final PromotionEntity promotion;

  /**
   * 绑定活动编号和种类名。不写数据库。
   *
   * @param promotion 要结束的活动
   */
  public PromotionFinishEvent(PromotionEntity promotion) {
    super(promotion.getPromotionId(), PromotionFinishEvent.class.getSimpleName());
    this.promotion = promotion;
  }

  /**
   * 把活动序列化成正文。不改库存。
   *
   * @param format JSON 工具
   * @return 活动的 JSON
   */
  @Override
  public String getContent(Format format) {
    return format.serialize(promotion);
  }
}
