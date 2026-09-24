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
 * │ AdminServiceApplication.java                    │
 * │ 链路：创建活动 · Admin 服务入口                 │
 * └─────────────────────────────────────────────────┘
 *
 * 浏览器 nginx /admin → Gateway 8085
 * │
 * ▼
 * 【本文件】启动 Admin 进程（端口 8081）
 * │
 * ▼
 * 控制器只写 PostgreSQL 活动行（Redis 库存未初始化）
 *
 * 一句话：入口只拉起进程，到 publishTime 才由 Command 初始化 Redis。
 */

package io.servicecomb.poc.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Admin 服务的进程入口，只负责创建和修改活动，不初始化 Redis 库存。
 * <p>
 * Spring 看到 {@code @SpringBootApplication} 后，会从本类所在的包往下扫描组件，并启动内嵌 HTTP
 * （端口在 {@code application.properties}，本服务是 8081）。浏览器只访问前端 nginx；nginx 把
 * {@code /admin} 转到 Gateway 8085，Gateway 再转到这里。
 * <p>
 * 本进程写入的是 PostgreSQL 里的活动行。到 {@code publishTime} 之后，才由 Command 服务初始化 Redis
 * 并记下 {@code PromotionStartEvent}。这里保存成功，不表示库存已经可以抢。
 */
@SpringBootApplication
public class AdminServiceApplication {

  /**
   * 创建 Spring 容器并开始监听 HTTP。
   *
   * @param args 命令行参数，Spring Boot 会把它并入配置，这里不解析业务含义
   */
  public static void main(String[] args) {
    SpringApplication.run(AdminServiceApplication.class, args);
  }
}
