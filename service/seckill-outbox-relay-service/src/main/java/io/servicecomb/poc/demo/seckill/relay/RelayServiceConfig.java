/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ RelayServiceConfig.java                         │
 * │ 链路：outbox · Relay 装配                       │
 * └─────────────────────────────────────────────────┘
 *
 * 【本文件】导入 Kafka 发布器配置
 * │
 * ▼
 * OutboxRelay / OutboxCdcListener 注入 SecKillEventPublisher
 *
 * 一句话：本类只声明要加载的库配置，不处理 HTTP。
 */

package io.servicecomb.poc.demo.seckill.relay;

import io.servicecomb.poc.demo.seckill.kafka.SecKillKafkaConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 把 Relay 服务需要的 Kafka 发布器装配进 Spring 容器。
 * <p>
 * {@code @Configuration} 表示本类只用来声明要加载的配置。{@code @Import(SecKillKafkaConfig.class)}
 * 会按 {@code seckill.infra.mode} 选择真实 Kafka 或内存总线。Relay 只发消息，不消费 topic。
 */
@Configuration
@Import(SecKillKafkaConfig.class)
class RelayServiceConfig {
}
