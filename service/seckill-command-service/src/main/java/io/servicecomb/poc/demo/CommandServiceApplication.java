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
 * │ CommandServiceApplication.java                  │
 * │ 链路：抢券 · Command 服务入口                    │
 * └─────────────────────────────────────────────────┘
 *
 * 【本文件】启动进程，打开事务（端口 8082）
 * │
 * ├─ publishTime ─▼ 初始化 Redis + PromotionStartEvent
 * ├─ 抢券 ─▼ Redis Lua（HTTP 成功时 PostgreSQL 还没有这张券）
 * └─ 提交之后 ─▼ outbox relay 发 Kafka
 *
 * 一句话：入口只拉起进程，热路径不在 HTTP 里写 PostgreSQL。
 */

package io.servicecomb.poc.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Command 服务的进程入口，负责抢券热路径，不负责把抢到的券写进 PostgreSQL。
 * <p>
 * Spring 看到 {@code @SpringBootApplication} 后，会从本类所在的包往下扫描，把
 * {@code @Component}、{@code @Configuration} 注册成 Bean，并启动内嵌 HTTP（端口在
 * {@code application.properties}，本服务是 8082）。浏览器只访问前端 nginx；Gateway 把
 * {@code POST /command/coupons/} 转到这里。
 * <p>
 * {@code @EnableTransactionManagement(proxyTargetClass = true)} 打开声明式事务，并强制用
 * 子类代理（CGLIB）。这样 {@code TransactionalEventOutboxWriter.persist} 上的
 * {@code @Transactional} 才会生效：事件行和 outbox 行在同一个 PostgreSQL 事务里提交。
 * 需要子类代理，是因为事务方法写在具体类上，不在单独的接口方法上。
 * <p>
 * 本进程里：到 {@code publishTime} 才初始化 Redis 并可能写入 {@code PromotionStartEvent}；
 * 抢券只做 Redis Lua；outbox 在事务提交之后才发到 Kafka。HTTP 返回成功时，PostgreSQL
 * 里通常还没有这张券的 {@code CouponGrabbedEvent}，那一行由 Persist 服务稍后写入。
 */
@SpringBootApplication
@EnableTransactionManagement(proxyTargetClass = true)
public class CommandServiceApplication {

  /**
   * 创建 Spring 容器并开始监听 HTTP。
   *
   * @param args 命令行参数，Spring Boot 会把它并入配置，这里不解析业务含义
   */
  public static void main(String[] args) {
    SpringApplication.run(CommandServiceApplication.class, args);
  }
}
