/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ OutboxCdcBootstrap.java                         │
 * │ 链路：outbox · CDC 触发器                       │
 * └─────────────────────────────────────────────────┘
 *
 * Relay 进程启动
 * │
 * ▼
 * 【本文件】在 PostgreSQL 安装 INSERT 触发器
 * │
 * └─ 新 outbox 行 ── NOTIFY seckill_outbox
 *
 * 一句话：把「行已提交」变成监听线程能收到的信号。
 */

package io.servicecomb.poc.demo.seckill.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 在 PostgreSQL 上安装 outbox 插入通知触发器，作为本项目的 CDC 触发源。
 * <p>
 * 这里没有引入 Debezium：教学场景里用 {@code NOTIFY}/{@code LISTEN} 就能在事务提交后立刻唤醒 Relay，
 * 语义是「变更数据捕获」的轻量版。只有 {@code seckill.outbox.cdc.enabled=true} 时装配本类；
 * H2 集成测试保持 false，只靠 {@link OutboxRelay} 轮询。
 * <p>
 * {@code @EventListener(ApplicationReadyEvent.class)} 表示 Spring 完全就绪后再执行 DDL，
 * 避免数据源还没准备好就建触发器。
 */
@Component
@ConditionalOnProperty(name = "seckill.outbox.cdc.enabled", havingValue = "true")
public class OutboxCdcBootstrap {

  /** 与 {@link OutboxCdcListener} 里 {@code LISTEN} 的频道名一致。 */
  static final String NOTIFY_CHANNEL = "seckill_outbox";

  private static final Logger logger = LoggerFactory.getLogger(OutboxCdcBootstrap.class);

  private final JdbcTemplate jdbcTemplate;

  /**
   * @param jdbcTemplate Spring 根据数据源创建的 JDBC 助手，用来执行建触发器 SQL
   */
  public OutboxCdcBootstrap(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 创建或替换通知函数与触发器。重复启动是幂等的。
   */
  @EventListener(ApplicationReadyEvent.class)
  public void installNotifyTrigger() {
    jdbcTemplate.execute("""
        CREATE OR REPLACE FUNCTION seckill_outbox_notify()
        RETURNS trigger AS $$
        BEGIN
          IF NEW.published = false THEN
            PERFORM pg_notify('seckill_outbox', NEW.id::text);
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """);
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS seckill_outbox_insert_notify ON outbox");
    jdbcTemplate.execute("""
        CREATE TRIGGER seckill_outbox_insert_notify
        AFTER INSERT ON outbox
        FOR EACH ROW EXECUTE FUNCTION seckill_outbox_notify()
        """);
    logger.info("Installed outbox CDC notify trigger on channel {}", NOTIFY_CHANNEL);
  }
}
