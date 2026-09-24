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
 * │ SecKillEvent.java                                    │
 * │ 链路：三种事件的父类                                 │
 * └──────────────────────────────────────────────────────┘
 *
 *   子类由 Command 或 Persist 创建
 *      │
 *      ▼
 *   【本文件】记下 promotionId 与 type
 *      │
 *      ▼
 *   SecKillEventFormat 转成事件行
 *
 * 一句话：父类只表示内存里的事件，不是数据库行。
 */

package io.servicecomb.poc.demo.seckill.event;

import io.servicecomb.poc.demo.seckill.Format;

/**
 * 三种秒杀事件的公共父类：开始、抢到券、结束。本身不是数据库行。
 * <p>
 * 子类由 Command（开始、到点结束）和 Persist（抢到券，以及卖完时的结束）创建，
 * 再经 {@link SecKillEventFormat} 变成 {@code EventEntity} 写入 PostgreSQL，并放进 outbox。
 * Kafka 上的消息是稍后 relay 发出的。查询侧要等 Event 服务投影到 Redis 之后才能看见券。
 * 本类不访问 Redis。构造器没有 {@code public}，只有同一包里的子类能调用。
 */
public abstract class SecKillEvent {

  /** 这场活动的业务编号，也是 Kafka 消息的 key。 */
  private final String promotionId;
  /** 事件种类，取子类的简单类名，与 {@link SecKillEventType} 里的常量相同。 */
  private final String type;

  /**
   * 记下活动和种类。不写数据库。
   *
   * @param promotionId 活动编号
   * @param type 简单类名，例如 {@code PromotionStartEvent}
   */
  SecKillEvent(String promotionId, String type) {
    this.promotionId = promotionId;
    this.type = type;
  }

  /** 活动编号。 */
  public String getPromotionId() {
    return promotionId;
  }

  /** 事件种类的简单类名。 */
  public String getType() {
    return type;
  }

  /**
   * 事件正文。基类没有额外字段，因此返回空 JSON 对象。子类改成序列化活动或券。
   *
   * @param format 序列化工具。基类实现不使用它
   * @return 固定为 {@code "{}"}
   */
  public String getContent(Format format) {
    return "{}";
  }
}
