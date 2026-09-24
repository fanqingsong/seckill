/*
 *   Copyright 2017 Huawei Technologies Co., Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

/*
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ SecKillEventFormat.java                              │
 * │ 链路：事件三种形态                                   │
 * └──────────────────────────────────────────────────────┘
 *
 *   Command、Persist 或 Event 服务
 *      │
 *      ▼
 *   【本文件】事件、消息、EventEntity 互转
 *      │
 *      ▼
 *   事件表与 outbox（本类不 save）
 *
 * 一句话：只做转换，不插入事件，也不发 Kafka。
 */

package io.servicecomb.poc.demo.seckill.event;

import io.servicecomb.poc.demo.seckill.Format;
import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.EventEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 在内存事件、Kafka/outbox 消息 {@link EventMessageDto}、PostgreSQL 行 {@link EventEntity} 之间转换。
 * <p>
 * Command 和 Persist 用 {@link #toMessage}、{@link #toEntity} 准备要和 outbox 一起提交的行。
 * Event 服务用 {@link #fromMessage} 把 Kafka 正文还原成三种事件，再投影到 Redis 和 Elasticsearch。
 * 本类只做转换，不 {@code save}，也不发消息。
 * <p>
 * {@code Map<String, Function<String, SecKillEvent>>} 里，键是事件简单类名，值是
 * {@code Function}：给它一段 JSON，它返回一个事件。下面的双大括号是在声明时创建
 * {@code HashMap} 的匿名子类，并在实例初始化块里 {@code put} 三种工厂。
 */
public class SecKillEventFormat {

  /** 真正做 JSON 的工具，由 {@link io.servicecomb.poc.demo.seckill.SecKillJacksonConfig} 注入。 */
  private final Format format;


  private final Map<String, Function<String, SecKillEvent>> eventFactories = new HashMap<String, Function<String, SecKillEvent>>() {{
    put(CouponGrabbedEvent.class.getSimpleName(), (content) -> couponGrabbedEvent(content));
    put(PromotionStartEvent.class.getSimpleName(), (content) -> promotionStartEvent(content));
    put(PromotionFinishEvent.class.getSimpleName(), (content) -> promotionFinishEvent(content));
  }};

  /** 底层 JSON 工具。outbox 序列化整条消息时会用到它。 */
  public Format getFormat() {
    return format;
  }

  /**
   * 保存 JSON 工具。不读库。
   *
   * @param format 序列化实现
   */
  public SecKillEventFormat(Format format) {
    this.format = format;
  }

  /**
   * 按消息里的类型把正文还原成事件。类型必须是三种简单类名之一。
   *
   * @param message Kafka 或 outbox 里的消息
   * @return 开始、抢到或结束事件。不写数据库
   */
  public SecKillEvent fromMessage(EventMessageDto message) {
    return generateEvent(message.getType(), message.getContent());
  }

  /**
   * 把事件表的一行还原成内存事件，回放时使用。
   *
   * @param entity PostgreSQL 里的事件行
   * @return 对应的内存事件
   */
  public SecKillEvent fromEntity(EventEntity entity) {
    return generateEvent(entity.getType(), entity.getContent());
  }

  /**
   * 没有现成编号时，新生成 {@code eventId}，序号用 0，时间用当前毫秒。
   * 序号 0 表示投影时不按缺口重排。不写库。
   *
   * @param event 内存事件
   * @return 可放进 outbox 的消息
   */
  public EventMessageDto toMessage(SecKillEvent event) {
    return toMessage(event, java.util.UUID.randomUUID().toString(), 0L);
  }

  /**
   * 用调用方已经定好的事件编号和序号组装消息。{@code customerId} 见 {@link #customerIdOf}。
   *
   * @param event 内存事件
   * @param eventId 事件业务编号
   * @param seq 活动内顺序号
   * @return 消息对象，尚未写入 PostgreSQL 或 Kafka
   */
  public EventMessageDto toMessage(SecKillEvent event, String eventId, long seq) {
    return new EventMessageDto(eventId, event.getPromotionId(), seq, event.getType(), System.currentTimeMillis(),
        event.getContent(format), customerIdOf(event));
  }

  /**
   * 先变成消息（序号为 0、新的 eventId），再变成表行。不 {@code save}。
   *
   * @param event 内存事件
   * @return 尚未插入的事件行
   */
  public EventEntity toEntity(SecKillEvent event) {
    return toEntity(toMessage(event));
  }

  /**
   * 按消息字段组装事件行，供和 outbox 放进同一个事务。
   *
   * @param message 已含 eventId、序号和正文的消息
   * @return 尚未插入的 {@link EventEntity}
   */
  public EventEntity toEntity(EventMessageDto message) {
    return new EventEntity(message.getEventId(), message.getSeq(), message.getType(), message.getPromotionId(),
        message.getCustomerId(), message.getContent(), message.getOccurredAt());
  }

  /**
   * 按类型找到工厂并还原事件。类型不在三种之内时，{@code get} 得到 null，接着调用会失败。
   *
   * @param type 简单类名
   * @param content 正文 JSON
   * @return 还原后的事件
   */
  private SecKillEvent generateEvent(String type, String content) {
    return eventFactories.get(type).apply(content);
  }

  /**
   * 决定写入事件表 {@code customerId} 列的值，好让唯一约束 {@code (promotionId, customerId)} 生效。
   * 不访问数据库。
   *
   * @param event 内存事件
   * @return 顾客编号、占位符，或未知类型时的 null
   */
  private String customerIdOf(SecKillEvent event) {
    if (event instanceof CouponGrabbedEvent) {
      // 抢到券：用真实顾客编号。同一活动同一人再插入一行会违反唯一约束。
      Object customerId = ((CouponGrabbedEvent<?>) event).getCoupon().getCustomerId();
      return customerId == null ? null : String.valueOf(customerId);
    }
    if (event instanceof PromotionStartEvent) {
      // 开始事件没有顾客。固定占位，避免和真实顾客或结束事件挤在同一组唯一键上。
      return "__start__";
    }
    if (event instanceof PromotionFinishEvent) {
      // 结束事件同样没有顾客，用另一个占位，和开始事件分开。
      return "__finish__";
    }
    return null;
  }

  /**
   * 把正文还原成抢券事件。{@code CouponEntity.class} 在泛型擦除后是原始类型，JSON 里的顾客按字段读回。
   *
   * @param content 券的 JSON
   * @return 抢券事件
   */
  private SecKillEvent couponGrabbedEvent(String content) {
    return new CouponGrabbedEvent<>(format.deserialize(content, CouponEntity.class));
  }

  /**
   * 把正文还原成开始事件。
   *
   * @param content 活动的 JSON
   * @return 开始事件
   */
  private SecKillEvent promotionStartEvent(String content) {
    return new PromotionStartEvent(format.deserialize(content, PromotionEntity.class));
  }

  /**
   * 把正文还原成结束事件。
   *
   * @param content 活动的 JSON
   * @return 结束事件
   */
  private SecKillEvent promotionFinishEvent(String content) {
    return new PromotionFinishEvent(format.deserialize(content, PromotionEntity.class));
  }
}
