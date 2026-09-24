/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ PersistOutboxWriter.java                        │
 * │ 链路：抢券 · Persist 落库                       │
 * └─────────────────────────────────────────────────┘
 *
 * GrabPersistWorker 已从 Redis 流取出令牌
 * │
 * ▼
 * 【本文件】同一事务写入事件行和 outbox
 * │
 * ▼
 * 提交之后由 Command 的 relay 发 Kafka（本文件不发）
 *
 * 一句话：事件和待发消息一起提交，避免只留下一半。
 */

package io.servicecomb.poc.demo.seckill.persist;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.event.SecKillEventType;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringOutboxRepository;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 Persist 服务要追加的事件和 outbox 放进同一个 PostgreSQL 事务。
 * <p>
 * {@code @Component} 表示 Spring 创建本对象。构造器参数是构造器注入。
 * 调用方是 {@link GrabPersistWorker}：它从 Redis 抢券流取出令牌后调用 {@link #persist}。
 * 本类不读 Redis，也不发 Kafka。outbox 行提交之后，由 Command 服务的 relay 发到
 * topic {@code seckill.events}。
 * <p>
 * {@code @Transactional} 的含义：别的 Bean 调用 {@link #persist} 时，Spring 先开事务。
 * 事件行和 outbox 行都 {@code save} 成功才提交；任一条失败则一起回滚。这样不会只留下事件、却没有待发消息。
 * <p>
 * Command 的 HTTP 成功发生在本方法之前，所以返回「已接受」时，这里的插入可能还没执行。
 */
@Component
public class PersistOutboxWriter {

  private final SpringSecKillEventRepository eventRepository;
  private final SpringOutboxRepository outboxRepository;
  private final SecKillEventFormat eventFormat;

  /**
   * @param eventRepository 追加事件。事件表只加行，不把剩余库存改成一张可变表
   * @param outboxRepository 保存与该事件对应的、尚未发往 Kafka 的 payload
   * @param eventFormat 消息和表行之间的转换
   */
  public PersistOutboxWriter(SpringSecKillEventRepository eventRepository,
      SpringOutboxRepository outboxRepository, SecKillEventFormat eventFormat) {
    this.eventRepository = eventRepository;
    this.outboxRepository = outboxRepository;
    this.eventFormat = eventFormat;
  }

  /**
   * 追加一条事件，并插入对应的 outbox 行。
   *
   * @param message 抢券或结束消息，已含 eventId、promotionId 和序号。outbox 保存序列化后的正文
   */
  @Transactional
  public void persist(EventMessageDto message) {
    eventRepository.save(eventFormat.toEntity(message));
    outboxRepository.save(new OutboxEntity(message.getEventId(), message.getPromotionId(),
        eventFormat.getFormat().serialize(message)));
  }

  /**
   * 该活动是否已经有结束事件，避免卖完时再写一条。
   *
   * @param promotionId 活动编号
   * @return 事件表中已有 {@code PromotionFinishEvent} 时为 true
   */
  public boolean hasFinishEvent(String promotionId) {
    // 只问有没有结束事件这一行。卖完最后一张时，不把已经抢到的券全部装进内存。
    return eventRepository.existsByPromotionIdAndType(promotionId, SecKillEventType.PromotionFinishEvent);
  }
}
