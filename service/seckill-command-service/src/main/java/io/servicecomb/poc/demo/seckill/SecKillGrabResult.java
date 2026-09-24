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
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ SecKillGrabResult.java                          │
 * │ 链路：抢券 · Command 结果                       │
 * └─────────────────────────────────────────────────┘
 *
 * Redis Lua 扣减
 * │
 * ▼
 * 【本文件】三种结果：成功 / 失败 / 重复
 * │
 * └─ Success ─▼ HTTP「已接受」（PostgreSQL 还没有这张券）
 *
 * 一句话：成功只描述 Redis 已扣，不是库里已有事件。
 */

package io.servicecomb.poc.demo.seckill;

/**
 * 一次抢券在 Redis 热路径上的三种结果。
 * <p>
 * {@link SecKillCommandService#addCouponTo} 返回本枚举，控制器再映射成 HTTP 状态。
 * {@link #Success} 只表示 Redis Lua 已经扣减，并把令牌放进抢券流。此时 PostgreSQL 里还没有
 * 这张券的事件行，Kafka 也还没发。{@link #Failed} 表示活动未开始、已结束或库存不足。
 * {@link #Duplicate} 表示同一活动里该顾客已经抢过。
 * <p>
 * {@code value} 是写在枚举常量上的编号。当前控制器按常量比较，不读取这个数字。
 */
public enum SecKillGrabResult {
  /** Redis 已扣减。查询库尚未更新。 */
  Success(0),
  /** 未开始、已结束或卖完。不写 PostgreSQL。 */
  Failed(1),
  /** 重复顾客。Lua 没有再次扣库存。 */
  Duplicate(2);

  /** 结果编号，仅保存在枚举实例上，没有对外的读取方法。 */
  private int value = 0;

  /**
   * @param value 上面对应常量括号里的编号
   */
  SecKillGrabResult(int value) {
    this.value = value;
  }
}
