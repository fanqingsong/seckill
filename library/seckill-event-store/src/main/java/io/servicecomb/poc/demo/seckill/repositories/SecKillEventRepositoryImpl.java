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
 * │ SecKillEventRepositoryImpl.java                      │
 * │ 链路：手动转交 save                                  │
 * └──────────────────────────────────────────────────────┘
 *
 *   手动 new 本类时传入仓库
 *      │
 *      ▼
 *   【本文件】把 save 转给 CrudRepository
 *      │
 *      ▼
 *   PostgreSQL 表 sec_kill_event
 *
 * 一句话：运行中的服务注入 Spring 仓库，不经过本类。
 */

package io.servicecomb.poc.demo.seckill.repositories;

import io.servicecomb.poc.demo.seckill.entities.EventEntity;
import org.springframework.data.repository.CrudRepository;

/**
 * {@link SecKillEventRepository} 的一个手动实现：只把 {@code save} 转给 Spring Data 的仓库。
 * <p>
 * 运行中的 Command、Persist、Event、Admin 注入的是
 * {@link io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository}，
 * 不经过本类。本类没有 Spring 的组件注解，容器不会自动创建它。
 * {@code save} 把一行追加进 PostgreSQL 的事件表，不写 Redis、不发 Kafka。
 * <p>
 * {@code CrudRepository<EventEntity, Integer>} 是 Spring Data 的通用仓库。尖括号里第一个是实体，
 * 第二个是主键类型，这里对应 {@code EventEntity} 的 {@code int} 主键。
 */
public class SecKillEventRepositoryImpl implements SecKillEventRepository {

  /** 真正执行插入的 Spring Data 仓库，由构造器传入。 */
  private final CrudRepository<EventEntity, Integer> repository;

  /**
   * 保存要委托的仓库。不访问数据库。
   *
   * @param repository 能保存 {@link EventEntity} 的 CrudRepository
   */
  public SecKillEventRepositoryImpl(CrudRepository<EventEntity, Integer> repository) {
    this.repository = repository;
  }

  /**
   * 把一行事件交给底层仓库保存。是否新插入取决于实体主键；本方法不更新 Redis，也不写 outbox。
   *
   * @param entity 要保存的事件行
   */
  @Override
  public void save(EventEntity entity) {
    repository.save(entity);
  }
}
