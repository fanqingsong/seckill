/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ InMemoryEventBus.java                         │
 * │ 链路：投递 · 内存总线                         │
 * └───────────────────────────────────────────────┘
 *
 * OutboxRelay
 *      │
 *      ▼
 *  【本文件】
 *      │
 *      ▼
 * 监听器（不连 Kafka）
 *
 * 一句话：mode 不是 prod 时，用本类代替 seckill.events。
 */

package io.servicecomb.poc.demo.seckill.kafka;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SecKillEventPublisher} 的内存实现，供 {@code seckill.infra.mode} 不是 {@code prod} 时使用。
 * <p>
 * 它不连接 Kafka，topic 名字也不会真正出现在网络上。{@link #publish} 在调用线程里同步通知
 * 已经 {@link #subscribe} 的监听器，Event 服务因此仍能把事件投影到内存 Redis 和内存搜索索引。
 * 死信只打一条警告日志，不再交给监听器。
 */
public class InMemoryEventBus implements SecKillEventPublisher {

  private static final Logger logger = LoggerFactory.getLogger(InMemoryEventBus.class);
  /** 订阅者列表。CopyOnWrite 让发布时可以遍历，同时允许 Event 服务在启动时再注册。 */
  private final List<SecKillEventListener> listeners = new CopyOnWriteArrayList<SecKillEventListener>();

  /**
   * 注册一个监听器。内存模式下 Event 服务把自己注册进来，相当于订阅 {@code seckill.events}。
   *
   * @param listener 收到正常事件后做投影的对象。重复注册会收到多次回调
   */
  public void subscribe(SecKillEventListener listener) {
    listeners.add(listener);
  }

  /**
   * 同步回调每个监听器。分区键在内存模式不参与路由。
   * <p>
   * 监听器抛出的异常会直接冒给调用方（OutboxRelay），不会自动改走死信。
   *
   * @param key 活动编号。本实现不使用它
   * @param payload 事件消息 JSON
   */
  @Override
  public void publish(String key, String payload) {
    for (SecKillEventListener listener : listeners) {
      listener.onEvent(payload);
    }
  }

  /**
   * 记录一条死信日志，等价于发到 {@code seckill.events.dlt}，但不通知监听器，也不落盘。
   *
   * @param key 原消息的分区键
   * @param payload 原消息正文
   */
  @Override
  public void publishDlt(String key, String payload) {
    logger.warn("DLT key={} payload={}", key, payload);
  }
}
