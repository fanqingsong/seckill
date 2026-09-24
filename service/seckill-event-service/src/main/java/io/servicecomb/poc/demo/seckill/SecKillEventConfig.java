/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ SecKillEventConfig.java                         │
 * │ 链路：投影 · 装配消费者                         │
 * └─────────────────────────────────────────────────┘
 *
 * seckill.infra.mode（默认 memory）
 * │
 * ▼
 * 【本文件】创建投影器并接上事件来源
 * │
 * ├── prod ──▶ Kafka 消费者线程（seckill.events）
 * └── memory ──▶ 订阅内存总线
 *
 * 一句话：回放不走这条消费线程，它直接读 PostgreSQL 事件表。
 */

package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.es.SecKillEsConfig;
import io.servicecomb.poc.demo.seckill.es.SecKillSearchIndex;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.kafka.InMemoryEventBus;
import io.servicecomb.poc.demo.seckill.kafka.KafkaSecKillEventConsumer;
import io.servicecomb.poc.demo.seckill.kafka.SecKillEventPublisher;
import io.servicecomb.poc.demo.seckill.kafka.SecKillKafkaConfig;
import io.servicecomb.poc.demo.seckill.redis.SecKillRedisConfig;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 装配 Event 服务的投影器，并按运行模式接上事件来源。
 * <p>
 * {@code @Configuration} 表示这个类只声明 Bean。{@code @Import} 加载 Redis、Kafka、Elasticsearch 的库配置，
 * 投影才会写到读模型和搜索索引。{@code @Bean} 方法的参数由 Spring 按类型注入。
 * 参数上的 {@code @Value} 读取配置：{@code seckill.infra.mode} 缺省是 {@code memory}，
 * {@code seckill.kafka.bootstrap} 缺省是 {@code 127.0.0.1:9092}。
 * <p>
 * 代码里和 {@code "prod"} 比较。只有这个值才会启动 Kafka 消费者。其它值如果发布器是内存总线，就订阅内存总线。
 * 回放不依赖这条消费线程，它直接读 PostgreSQL。
 */
@Configuration
@Import({SecKillRedisConfig.class, SecKillKafkaConfig.class, SecKillEsConfig.class})
public class SecKillEventConfig {

  /**
   * 创建投影器，并让它开始接收事件。
   *
   * @param eventFormat 把消息 JSON 还原成具体事件
   * @param store Redis 读模型
   * @param searchIndex Elasticsearch 索引
   * @param eventRepository 回放和补洞时读取的事件表
   * @param mode {@code seckill.infra.mode}，等于 {@code prod} 时走 Kafka
   * @param bootstrap Kafka 地址，仅 {@code prod} 分支使用
   * @param publisher 事件发布器；内存模式下若它是 {@link InMemoryEventBus}，投影器订阅它
   * @return 已经接上事件来源的投影器，回放接口也用同一个对象
   */
  @Bean
  EventProjector eventProjector(SecKillEventFormat eventFormat, SecKillStore store, SecKillSearchIndex searchIndex,
      SpringSecKillEventRepository eventRepository,
      @Value("${seckill.infra.mode:memory}") String mode,
      @Value("${seckill.kafka.bootstrap:127.0.0.1:9092}") String bootstrap,
      SecKillEventPublisher publisher) {
    EventProjector projector = new EventProjector(eventFormat, store, searchIndex, eventRepository);
    // 配置写成 prod：单独起一个线程，从 Kafka topic 拉消息再投影。
    if ("prod".equals(mode)) {
      KafkaSecKillEventConsumer consumer = new KafkaSecKillEventConsumer(bootstrap, projector, publisher);
      Executors.newSingleThreadExecutor().submit(consumer);
    } else if (publisher instanceof InMemoryEventBus) {
      // 测试和默认 memory：没有 Kafka。发布器若是内存总线，投影器直接订阅它。
      ((InMemoryEventBus) publisher).subscribe(projector);
    }
    return projector;
  }
}
