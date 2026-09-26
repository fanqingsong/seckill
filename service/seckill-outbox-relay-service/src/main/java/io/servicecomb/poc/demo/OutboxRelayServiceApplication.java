/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ OutboxRelayServiceApplication.java              │
 * │ 链路：outbox · Relay 服务入口                   │
 * └─────────────────────────────────────────────────┘
 *
 * Command / Persist 已在同一事务提交 outbox
 * │
 * ▼
 * 【本文件】启动 Relay 进程（端口 8087）
 * │
 * ├── CDC（PostgreSQL NOTIFY）──▶ 尽快投递
 * └── 轮询 ──▶ 漏网行兜底
 * │
 * └─ Kafka seckill.events
 *
 * 一句话：本进程不接 HTTP 业务；只把已提交的 outbox 发到 Kafka。
 */

package io.servicecomb.poc.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Outbox Relay 服务的进程入口。Command 和 Persist 写完 outbox 并提交事务之后，由本进程投递到 Kafka。
 * <p>
 * Spring 看到 {@code @SpringBootApplication} 后扫描本包及子包，注册 relay 相关 {@code @Component}。
 * 端口在 {@code application.properties}，本服务是 8087。Gateway 和浏览器都不会把请求转到这里。
 * <p>
 * 生产形态下，PostgreSQL 触发器在 outbox 插入后 {@code NOTIFY}，{@code OutboxCdcListener} 收到通知后投递；
 * 同时 {@code OutboxRelay} 仍按固定间隔轮询未发布行，防止通知丢失。测试默认 {@code seckill.outbox.cdc.enabled=false}，
 * 只靠轮询，与集成测试用的 H2 兼容。
 */
@SpringBootApplication
@EnableTransactionManagement(proxyTargetClass = true)
public class OutboxRelayServiceApplication {

  /**
   * 创建 Spring 容器。容器起来后，relay 的轮询线程和（若开启）CDC 监听线程会开始工作。
   *
   * @param args 命令行参数，Spring Boot 会把它并入配置
   */
  public static void main(String[] args) {
    SpringApplication.run(OutboxRelayServiceApplication.class, args);
  }
}
