/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ OutboxRelay.java                                │
 * │ 链路：outbox · 轮询兜底                         │
 * └─────────────────────────────────────────────────┘
 *
 * 事务已提交的 outbox 行
 * │
 * ▼
 * 【本文件】定时扫描 published=false
 * │
 * └──▶ OutboxRowPublisher ──▶ Kafka
 *
 * 一句话：CDC 漏掉或失败时，靠轮询把行发出去。
 */

package io.servicecomb.poc.demo.seckill.relay;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 以固定间隔轮询未发布的 outbox 行，作为 CDC 的兜底。
 * <p>
 * {@code @Component} 表示 Spring 启动时创建本类。构造器里启动单线程定时器，避免与 CDC 线程同时改同一行。
 * Command 和 Persist 只写 outbox，不在 HTTP 或 Redis 消费路径里发 Kafka。
 * <p>
 * PostgreSQL 已提交但 NOTIFY 丢失、或 Relay 进程当时不在线时，本类的轮询仍会在下一轮把行发到
 * topic {@code seckill.events}。Event 服务按序号去重，重复投递已发布的行由 {@link OutboxRowPublisher} 跳过。
 */
@Component
public class OutboxRelay {

  private static final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRowPublisher rowPublisher;
  private final ScheduledExecutorService scheduler;

  /**
   * 保存投递逻辑，并按配置启动轮询。
   *
   * @param rowPublisher 共享的单行/批量发布逻辑
   * @param pollIntervalMs {@code seckill.outbox.relay.poll-interval-ms}，每轮结束后再等待的毫秒数
   */
  public OutboxRelay(OutboxRowPublisher rowPublisher,
      @Value("${seckill.outbox.relay.poll-interval-ms:200}") long pollIntervalMs) {
    this.rowPublisher = rowPublisher;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "outbox-relay-poll");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.scheduleWithFixedDelay(this::pollPending, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
  }

  /**
   * 容器关闭时停掉轮询线程。
   */
  @PreDestroy
  public void stop() {
    scheduler.shutdownNow();
  }

  /**
   * 供 CDC 监听器在收到 NOTIFY 后按 id 触发投递。与轮询共用 {@link OutboxRowPublisher}。
   *
   * @param outboxId outbox 主键
   */
  public void wakeForOutboxId(long outboxId) {
    try {
      rowPublisher.publishIfPending(outboxId);
    } catch (RuntimeException e) {
      logger.warn("Outbox relay CDC wake failed for id={}: {}", outboxId, e.getMessage());
    }
  }

  /**
   * 定时任务入口：批量扫描未发布行。
   */
  void pollPending() {
    try {
      rowPublisher.publishPendingBatch();
    } catch (RuntimeException e) {
      logger.warn("Outbox relay poll failed: {}", e.getMessage());
    }
  }
}
