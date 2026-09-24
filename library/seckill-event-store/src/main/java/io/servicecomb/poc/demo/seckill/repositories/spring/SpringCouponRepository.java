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

import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link CouponEntity} 的 Spring Data JPA 仓库，操作的是 PostgreSQL 里该实体对应的表。
 * <p>
 * 接口上的 {@code <T>} 与券里的顾客编号类型一致。{@code extends JpaRepository<CouponEntity<T>, Integer>}
 * 表示：Spring 会在启动时生成实现类，主键类型是 {@code Integer}。方法名
 * {@code findByIdGreaterThan} 会被翻译成「{@code id} 大于给定值」的查询，调用方不用写 SQL。
 * 查询页的「我的券」读的是 Redis；搜索读 Elasticsearch。Event 服务投影券时调用的是 Redis 存储，
 * 不是本接口的 {@code save}。集成测试会注入本接口并 {@code deleteAll}。
 *
 * @param <T> 顾客编号的类型
 */
public interface SpringCouponRepository<T> extends JpaRepository<CouponEntity<T>, Integer> {

  /**
   * 查出自增 id 大于 {@code id} 的券，用于按编号增量读取。
   *
   * @param id 只返回比这个 id 更大的行
   * @return 符合条件的券，没有则为空列表。不修改库存
   */
  List<CouponEntity<T>> findByIdGreaterThan(int id);
}
