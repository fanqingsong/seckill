/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ EventServiceApplication.java                    │
 * │ 链路：投影 · Event 服务入口                     │
 * └─────────────────────────────────────────────────┘
 *
 * Kafka topic seckill.events（测试为内存总线）
 * │
 * ▼
 * 【本文件】启动 Event 进程（端口 8084）
 * │
 * ├── 消费消息 ──▶ Redis 读模型 + Elasticsearch
 * └── POST /admin/replay ──▶ 直接读事件表（nginx 不代理）
 *
 * 一句话：浏览器 nginx 不把回放转到这里，查询页要等投影完成后才有券。
 */

package io.servicecomb.poc.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Event 服务的进程入口。它消费 Kafka（或测试里的内存总线），把事件投影到 Redis 读模型和 Elasticsearch。
 * <p>
 * Spring 看到 {@code @SpringBootApplication} 后，会从本类所在的包往下扫描组件，并启动内嵌 HTTP
 * （端口在 {@code application.properties}，本服务是 8084）。浏览器只访问前端 nginx，nginx 不把回放接口
 * 代理到这里。回放 {@code POST /admin/replay} 挂在本进程上，供运维或测试直接调用。
 * <p>
 * 本进程不处理抢券。抢券成功后，Persist 把事件写入 PostgreSQL，outbox 再发到 Kafka；本服务读到消息后，
 * Query 服务才能从 Redis 或搜索索引里看见结果。
 */
@SpringBootApplication
public class EventServiceApplication {

  /**
   * 创建 Spring 容器并开始监听 HTTP，同时由配置类拉起事件消费。
   *
   * @param args 命令行参数，Spring Boot 会把它并入配置，这里不解析业务含义
   */
  public static void main(String[] args) {
    SpringApplication.run(EventServiceApplication.class, args);
  }
}
