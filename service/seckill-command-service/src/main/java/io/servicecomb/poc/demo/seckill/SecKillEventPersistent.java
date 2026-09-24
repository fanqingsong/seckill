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

package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.event.SecKillEvent;

/**
 * 把一条秒杀事件追加进 PostgreSQL 的入口。
 * <p>
 * 当前实现是 {@link TransactionalEventOutboxWriter}：同一次数据库事务里写事件表和 outbox 表。
 * 本接口不访问 Redis，也不直接发 Kafka。Kafka 由 {@link OutboxRelay} 在事务提交之后投递。
 * <p>
 * 抢券 HTTP 成功只表示 Redis 已扣减，不会通过本接口立刻写出 {@code CouponGrabbedEvent}。
 * 那条事件由 Persist 服务消费 Redis 抢券流后再写。
 */
public interface SecKillEventPersistent {

  /**
   * 持久化一条领域事件。
   *
   * @param event 开始、抢到或结束事件。实现类把它转成消息，再写入事件表和 outbox
   */
  void persistEvent(SecKillEvent event);
}
