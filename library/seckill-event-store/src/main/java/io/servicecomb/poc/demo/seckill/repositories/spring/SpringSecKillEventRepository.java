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

package io.servicecomb.poc.demo.seckill.repositories.spring;

import io.servicecomb.poc.demo.seckill.entities.EventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 事件表 {@code sec_kill_event} 的 Spring Data 仓库。数据在 PostgreSQL，不在 Redis。
 * <p>
 * Command 用它追加开始事件、结束事件，并按活动查找是否已经写过结束。Persist 用它追加
 * {@code CouponGrabbedEvent}。Event 服务回放时按序号读取。Admin 在修改活动前用它看
 * 是否已经有开始事件。抢券 HTTP 线程不调用本接口。
 * <p>
 * {@code JpaRepository<EventEntity, Integer>} 的第二个类型参数是主键 {@code id} 的类型。
 * 只声明方法、不写方法体：Spring 按方法名生成查询。{@code OrderBySeqAsc} 表示结果按
 * {@code seq} 从小到大。
 */
public interface SpringSecKillEventRepository extends JpaRepository<EventEntity, Integer> {

  /**
   * 取出一场活动已经追加的全部事件，不保证顺序。
   *
   * @param promotionId 活动编号
   * @return 该活动的事件行。不修改表
   */
  List<EventEntity> findByPromotionId(String promotionId);

  /**
   * 从某个序号起按顺序取出事件，供回放补齐缺口。
   *
   * @param promotionId 活动编号
   * @param seq 包含这个序号以及更大的序号
   * @return 按 {@code seq} 升序排列的事件。不写 Kafka
   */
  List<EventEntity> findByPromotionIdAndSeqGreaterThanEqualOrderBySeqAsc(String promotionId, long seq);
}
