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
 * │ SecKillEventType.java                                │
 * │ 链路：事件种类常量                                   │
 * └──────────────────────────────────────────────────────┘
 *
 *   Command、Persist、Event、Admin
 *      │
 *      ▼
 *   【本文件】三种 type 字符串常量
 *      │
 *      ▼
 *   与事件表 type 列对照
 *
 * 一句话：常量等于类的简单名，本类不写存储。
 */

package io.servicecomb.poc.demo.seckill.event;

/**
 * 三种事件种类的字符串常量，值和对应类的简单类名相同。
 * <p>
 * Command、Persist、Event、Admin 用这些字符串和事件表的 {@code type} 列比较，
 * 避免各处手写一遍拼写。比较发生在已经读出的 PostgreSQL 行或内存事件上，
 * 本类自己不写 Redis、Kafka 或 Elasticsearch。它不是枚举，只是一组 {@code public static final} 字符串。
 */
public class SecKillEventType {
  /** 库存已在 Redis 初始化。由 Command 在 {@code publishTime} 追加到事件表。 */
  public static final String PromotionStartEvent = "PromotionStartEvent";
  /**
   * 某人抢到一张券。由 Persist 从 Redis 队列取出令牌后写入事件表，
   * 不是抢券 HTTP 线程直接插入。
   */
  public static final String CouponGrabbedEvent = "CouponGrabbedEvent";
  /** 活动结束：券已卖完，或到了结束时间且抢券队列已空。 */
  public static final String PromotionFinishEvent = "PromotionFinishEvent";

}
