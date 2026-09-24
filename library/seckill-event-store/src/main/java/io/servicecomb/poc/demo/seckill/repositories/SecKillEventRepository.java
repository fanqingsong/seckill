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

package io.servicecomb.poc.demo.seckill.repositories;

import io.servicecomb.poc.demo.seckill.entities.EventEntity;

/**
 * 只暴露「保存一行事件」的窄接口。实现类把调用转给 Spring Data。
 * <p>
 * 各服务实际注入的是 {@link io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository}，
 * 那个接口还能按活动和序号查询。本接口保存的是 PostgreSQL 事件表，不写 Redis 或 Kafka。
 */
public interface SecKillEventRepository {

  /**
   * 保存一行追加事件。
   *
   * @param entity 事件行。调用方负责已经填好类型和正文
   */
  void save(EventEntity entity);
}
