/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ OutboxCdcListener.java                          │
 * │ 链路：outbox · CDC 监听                        │
 * └─────────────────────────────────────────────────┘
 *
 * PostgreSQL outbox INSERT 触发 NOTIFY
 * │
 * ▼
 * 【本文件】LISTEN seckill_outbox
 * │
 * └──▶ OutboxRelay.wakeForOutboxId ──▶ Kafka
 *
 * 一句话：比轮询更快；轮询仍负责兜底。
 */

package io.servicecomb.poc.demo.seckill.relay;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 在独立 JDBC 连接上 {@code LISTEN} outbox 插入频道，收到 NOTIFY 后立刻触发 relay。
 * <p>
 * {@code LISTEN} 必须占用一条长连接，不能和 JPA 连接池混用同一条连接，因此本类自己从
 * {@link DataSource} 取连接并专门用于监听。通知 payload 是 outbox 主键字符串，交给
 * {@link OutboxRelay#wakeForOutboxId(long)} 与轮询共用发布逻辑。
 * <p>
 * {@code @ConditionalOnProperty}：只有生产 Compose 打开 {@code seckill.outbox.cdc.enabled=true}
 * 时才创建本 Bean。测试与 H2 不装配，避免调用 PostgreSQL 专有 API。
 */
@Component
@ConditionalOnProperty(name = "seckill.outbox.cdc.enabled", havingValue = "true")
public class OutboxCdcListener {

  private static final Logger logger = LoggerFactory.getLogger(OutboxCdcListener.class);

  private final DataSource dataSource;
  private final OutboxRelay outboxRelay;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private Connection listenConnection;
  private Thread listenerThread;

  /**
   * @param dataSource 用来打开专用 LISTEN 连接
   * @param outboxRelay 收到 id 后触发投递（内部调用 {@link OutboxRowPublisher}）
   */
  public OutboxCdcListener(DataSource dataSource, OutboxRelay outboxRelay) {
    this.dataSource = dataSource;
    this.outboxRelay = outboxRelay;
  }

  /**
   * 应用就绪后启动监听线程。在 {@link OutboxCdcBootstrap} 安装触发器之后执行。
   */
  @EventListener(ApplicationReadyEvent.class)
  public void startListening() {
    listenerThread = new Thread(this::listenLoop, "outbox-cdc-listen");
    listenerThread.setDaemon(true);
    listenerThread.start();
  }

  /**
   * 关闭 LISTEN 连接并中断监听线程。
   */
  @PreDestroy
  public void stop() {
    running.set(false);
    if (listenerThread != null) {
      listenerThread.interrupt();
    }
    if (listenConnection != null) {
      try {
        listenConnection.close();
      } catch (SQLException e) {
        logger.debug("Closing LISTEN connection failed: {}", e.getMessage());
      }
    }
  }

  /**
   * 阻塞读取 NOTIFY，直到进程关闭。连接断开会打日志并结束线程，轮询仍会继续投递。
   */
  void listenLoop() {
    try {
      listenConnection = dataSource.getConnection();
      listenConnection.setAutoCommit(true);
      try (Statement statement = listenConnection.createStatement()) {
        statement.execute("LISTEN " + OutboxCdcBootstrap.NOTIFY_CHANNEL);
      }
      PGConnection pgConnection = listenConnection.unwrap(PGConnection.class);
      while (running.get() && !Thread.currentThread().isInterrupted()) {
        PGNotification[] notifications = pgConnection.getNotifications(5000);
        if (notifications == null) {
          continue;
        }
        for (PGNotification notification : notifications) {
          dispatch(notification.getParameter());
        }
      }
    } catch (SQLException | RuntimeException e) {
      if (running.get()) {
        logger.warn("Outbox CDC listener stopped: {}", e.getMessage());
      }
    }
  }

  /**
   * 把 NOTIFY payload 解析成 outbox id 并唤醒 relay。
   *
   * @param outboxIdText 触发器里 {@code NEW.id::text}
   */
  void dispatch(String outboxIdText) {
    try {
      long outboxId = Long.parseLong(outboxIdText);
      outboxRelay.wakeForOutboxId(outboxId);
    } catch (NumberFormatException e) {
      logger.warn("Ignored outbox NOTIFY with bad id: {}", outboxIdText);
    }
  }
}
