/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ TransactionalEventOutboxWriter.java             │
 * │ 链路：抢券 · Command 落库                       │
 * └─────────────────────────────────────────────────┘
 *
 * publishTime 或活动结束
 * │
 * ▼
 * 【本文件】同一事务写入事件行和 outbox
 * │
 * ▼
 * 提交之后由 relay 发 Kafka（本文件不发）
 *
 * 一句话：开始和结束走这里；抢券成功的券行由 Persist 另写。
 */

package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import io.servicecomb.poc.demo.seckill.event.SecKillEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.event.SecKillEventType;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringOutboxRepository;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在同一个 PostgreSQL 事务里追加事件行和 outbox 行。
 * <p>
 * {@code @Component} 让 Spring 创建本对象。构造器参数是构造器注入：仓库和事件格式由容器传入。
 * 活动开始、按时间结束走这里。抢券成功的 {@code CouponGrabbedEvent} 由 Persist 服务写入，
 * 不经过本类。本类不访问 Redis。事务提交之后，Outbox Relay 服务才把 outbox 发到 Kafka。
 * <p>
 * {@code @Transactional} 标在 {@link #persist} 上。别的 Bean 调用这个方法时，Spring 先开一个
 * 数据库事务，两条 {@code save} 都成功才提交；任一抛异常则事件行和 outbox 行一起回滚。
 * 因此不会出现「事件已经记下，但 outbox 没记下，Kafka 永远看不到」这种只写一半的情况。
 * <p>
 * 抢券 HTTP 返回成功时，本类通常还没有那条抢券事件，PostgreSQL 里也还没有对应的行。
 */
@Component
public class TransactionalEventOutboxWriter implements SecKillEventPersistent {

  private final SpringSecKillEventRepository eventRepository;
  private final SpringOutboxRepository outboxRepository;
  private final SecKillEventFormat eventFormat;

  /**
   * @param eventRepository 追加事件，不按业务字段更新旧行
   * @param outboxRepository 保存待发往 Kafka 的 payload
   * @param eventFormat 领域事件、消息和表行之间的转换
   */
  public TransactionalEventOutboxWriter(SpringSecKillEventRepository eventRepository,
      SpringOutboxRepository outboxRepository, SecKillEventFormat eventFormat) {
    this.eventRepository = eventRepository;
    this.outboxRepository = outboxRepository;
    this.eventFormat = eventFormat;
  }

  /**
   * 把领域事件转成消息后交给 {@link #persist}。不改 Redis 库存。
   *
   * @param event 要追加的开始、抢到或结束事件
   */
  @Override
  public void persistEvent(SecKillEvent event) {
    persist(eventFormat.toMessage(event));
  }

  /**
   * 追加一条事件，并插入对应的 outbox 行。两条写入处在同一个事务里。
   *
   * @param message 已带 eventId、promotionId 和序号的消息。outbox 保存它序列化后的正文
   */
  @Transactional
  public void persist(EventMessageDto message) {
    eventRepository.save(eventFormat.toEntity(message));
    outboxRepository.save(new OutboxEntity(message.getEventId(), message.getPromotionId(),
        eventFormat.getFormat().serialize(message)));
  }

  /**
   * 事件表里是否已经有该活动的结束事件。
   *
   * @param promotionId 活动编号
   * @return 已有 {@code PromotionFinishEvent} 时为 true，调用方不应再追加一条
   */
  public boolean hasFinishEvent(String promotionId) {
    // 只问有没有结束事件这一行。到点结束时，不把该活动已经抢到的券全部装进内存。
    return eventRepository.existsByPromotionIdAndType(promotionId, SecKillEventType.PromotionFinishEvent);
  }
}
