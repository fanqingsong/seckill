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
 * ┌─ 文件 ───────────────────────────────────┐
 * │ IntegrationTestApplication.java         │
 * │ 场景：集成测试共用的 Spring Boot 启动类  │
 * └─────────────────────────────────────────┘
 *
 * @SpringBootTest(classes = 本类)
 * │
 * ▼
 * 【本文件】从本包扫描 Bean，并打开类代理事务
 * │
 * └──▶ 创建、抢券、查询打在同一个应用上
 *
 * 一句话：测试不调用 main，只把它当作配置源。
 */
package io.servicecomb.poc.demo.seckill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 集成测试用的 Spring Boot 启动类。{@link SecKillIntegrationTest} 等测试通过
 * {@code @SpringBootTest(classes = IntegrationTestApplication.class)} 拉起它，而不是单独启动某一个服务。
 * <p>
 * {@code @SpringBootApplication} 表示 Spring 从本包开始扫描并装配 Bean。
 * {@code @EnableTransactionManagement(proxyTargetClass = true)} 打开事务，并使用类代理，
 * 这样写事件和 outbox 可以放在同一个数据库事务里。
 * {@code main} 只是本地启动入口，测试本身不会调用它。
 * 本文件没有声明 Redis、Kafka、Elasticsearch 或 H2。
 */
@SpringBootApplication
@EnableTransactionManagement(proxyTargetClass = true)
public class IntegrationTestApplication {
  /**
   * 用本类作为配置源启动 Spring。集成测试不走这个方法。
   *
   * @param args 命令行参数，测试里不会用到
   */
  public static void main(String[] args) {
    SpringApplication.run(IntegrationTestApplication.class, args);
  }
}
