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

import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活动定义表的 Spring Data 仓库，读写 PostgreSQL 里的 {@link PromotionEntity}。
 * <p>
 * Admin 创建、修改活动时使用它。Command 启动后按 id 增量拉出活动，到 {@code publishTime}
 * 再初始化 Redis。删除只删活动定义，不会在这里扣库存或发 Kafka。
 * <p>
 * {@code JpaRepository<PromotionEntity, Integer>} 的主键类型是自增 {@code int} 对应的 {@code Integer}。
 * {@code @Transactional} 标在删除方法上：Spring Data 由方法名生成的删除语句必须在事务里执行，
 * 否则启动或调用时会报错。这里的事务只包住这一次删除。
 */
public interface SpringPromotionRepository extends JpaRepository<PromotionEntity, Integer> {

  /**
   * 查出自增 id 大于给定值的活动，便于调用方记住上次读到哪，下次只取新行。
   *
   * @param id 下限，不包含这个 id
   * @return 更新的活动定义。不初始化 Redis
   */
  List<PromotionEntity> findByIdGreaterThan(int id);

  /**
   * 按业务编号取一条活动。{@code Top} 表示最多一条。
   *
   * @param promotionId 活动业务编号
   * @return 找到则返回该活动，没有则为 null
   */
  PromotionEntity findTopByPromotionId(String promotionId);

  /**
   * 按业务编号删除活动定义。只影响 PostgreSQL 里的活动行。
   *
   * @param promotionId 要删除的活动编号
   */
  @Transactional
  void deleteByPromotionId(String promotionId);
}
