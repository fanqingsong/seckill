/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ SecKillKafkaConfig.java                       │
 * │ 链路：启动 · 选择发布器                       │
 * └───────────────────────────────────────────────┘
 *
 * Spring 创建 @Bean
 *        │
 *        ▼
 *    【本文件】
 *  mode 是否等于 prod
 *   ├─ 是 ─ Kafka
 *   └─ 否 ─ 内存总线
 *
 * 一句话：profile 名不选客户端；mode=prod 才连 Kafka。
 */

package io.servicecomb.poc.demo.seckill.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置选择事件发布器：真实 Kafka，或进程内的内存总线。
 * <p>
 * {@code @Configuration} 表示 Spring 启动时加载本类。{@code @Bean} 表示下面方法的返回值放进容器，
 * OutboxRelay 用构造器注入拿到它。本类只创建发布器，不启动消费者。Event 服务在
 * {@code seckill.infra.mode} 等于 {@code prod} 时自己 new {@link KafkaSecKillEventConsumer}；
 * 内存模式下则调用 {@link InMemoryEventBus#subscribe}。
 */
@Configuration
public class SecKillKafkaConfig {

  /**
   * 创建事件发布器。
   * <p>
   * 条件在方法体内：{@code seckill.infra.mode} 等于 {@code prod} 时用
   * {@code seckill.kafka.bootstrap}（缺省 {@code 127.0.0.1:9092}）连接 Kafka。
   * 其它值，包括缺省的 {@code memory}，返回 {@link InMemoryEventBus}。测试因此不用起 Kafka。
   *
   * @param mode {@code seckill.infra.mode}
   * @param bootstrap {@code seckill.kafka.bootstrap}，只在 {@code prod} 时使用
   * @return 放进 Spring 容器的发布器
   */
  @Bean
  public SecKillEventPublisher secKillEventPublisher(
      @Value("${seckill.infra.mode:memory}") String mode,
      @Value("${seckill.kafka.bootstrap:127.0.0.1:9092}") String bootstrap) {
    // 只有字符串恰好是 prod 才连接 Kafka。
    if ("prod".equals(mode)) {
      return new KafkaSecKillEventPublisher(bootstrap);
    }
    return new InMemoryEventBus();
  }
}
