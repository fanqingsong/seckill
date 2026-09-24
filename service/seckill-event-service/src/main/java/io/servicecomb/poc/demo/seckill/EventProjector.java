package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.EventEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.es.SecKillSearchIndex;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionFinishEvent;
import io.servicecomb.poc.demo.seckill.event.PromotionStartEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.event.SecKillEventType;
import io.servicecomb.poc.demo.seckill.kafka.SecKillEventListener;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把秒杀事件投影到 Redis 读模型和 Elasticsearch。Query 服务读的就是这里写出来的数据。
 * <p>
 * 本类实现 {@link SecKillEventListener}：Kafka 消费者或内存总线每来一条消息就调用 {@link #onEvent}。
 * 它也按序号处理乱序：序号跳过时先放进 Redis 缓冲，再从 PostgreSQL 把缺口补上。
 * {@link #replay} 直接读事件表重放，不经过浏览器或 nginx。
 * <p>
 * 三种事件：{@code PromotionStartEvent} 写入进行中的活动；{@code CouponGrabbedEvent} 写入券；
 * {@code PromotionFinishEvent} 把活动从进行中列表移走，并在搜索索引里标成结束。
 * 抢券 HTTP 成功时本类可能还没跑到，所以查询页会晚一点才看见券。
 */
public class EventProjector implements SecKillEventListener {

  private static final Logger logger = LoggerFactory.getLogger(EventProjector.class);

  private final SecKillEventFormat eventFormat;
  private final SecKillStore store;
  private final SecKillSearchIndex searchIndex;
  private final SpringSecKillEventRepository eventRepository;

  /**
   * @param eventFormat 消息与具体事件类型之间的转换
   * @param store Redis 读模型，也保存已投影序号和乱序缓冲
   * @param searchIndex Elasticsearch，活动和券都会写入
   * @param eventRepository 补洞和回放时读取的 PostgreSQL 事件表，本类不往事件表插入新行
   */
  public EventProjector(SecKillEventFormat eventFormat, SecKillStore store, SecKillSearchIndex searchIndex,
      SpringSecKillEventRepository eventRepository) {
    this.eventFormat = eventFormat;
    this.store = store;
    this.searchIndex = searchIndex;
    this.eventRepository = eventRepository;
  }

  /**
   * 消费一条原始消息。{@code @Override} 表示这是接口规定的方法，签名不能改。
   *
   * @param payload Kafka 或内存总线上的 JSON 正文，本方法把它还原后再投影
   */
  @Override
  public void onEvent(String payload) {
    EventMessageDto message = eventFormat.getFormat().deserialize(payload, EventMessageDto.class);
    project(message);
  }

  /**
   * 按序号决定这条消息是丢弃、缓冲补洞，还是立刻写入读模型。
   * <p>
   * 序号 0 不参与「已投影到哪」的比较，仍会 {@link #apply}，但不会改已投影序号。
   *
   * @param message 一条已反序列化的事件，带活动编号和序号
   */
  public void project(EventMessageDto message) {
    long applied = store.appliedSeq(message.getPromotionId());
    // 序号比已经投影过的还小：重复或过期消息，不再写 Redis / Elasticsearch。
    if (message.getSeq() != 0 && message.getSeq() < applied) {
      return;
    }
    // 序号跳过了下一号：先问事件表。表里有的行直接投影（中间没落库的序号会被跳过），
    // 表里还没有的才放进缓冲，等下一条按序事件或回放。不要用 project 再走一遍缺口判断，
    // 否则同一条缺号事件会把自己再缓冲成千上万次，Kafka 消费线程会被拖死，查询页一直没有券。
    if (message.getSeq() != 0 && message.getSeq() > applied + 1) {
      fillGap(message.getPromotionId(), applied + 1);
      if (message.getSeq() > store.appliedSeq(message.getPromotionId())) {
        store.buffer(message);
      } else {
        drain(message.getPromotionId());
      }
      return;
    }
    applyIfNew(message);
    drain(message.getPromotionId());
  }

  /**
   * 从 PostgreSQL 事件表按序号重放，再次写入 Redis 和 Elasticsearch。
   * <p>
   * 不发新的 Kafka 消息，也不从浏览器这条 nginx 代理进来。
   *
   * @param promotionId 活动编号
   * @param fromSeq 起始序号，包含这一条；查询按序号升序
   */
  public void replay(String promotionId, long fromSeq) {
    applyStored(promotionId, fromSeq);
    drain(promotionId);
  }

  /**
   * 用事件表补上跳过的序号。补洞失败只记日志，不把异常抛回消费线程。
   *
   * @param promotionId 出现缺口的活动
   * @param fromSeq 第一个还没投影的序号
   */
  private void fillGap(String promotionId, long fromSeq) {
    try {
      applyStored(promotionId, fromSeq);
    } catch (RuntimeException e) {
      // 事件表暂时读不到或投影失败：这条缺口留到下次消息或显式回放再补。
      logger.warn("Gap fill failed for {} fromSeq={}", promotionId, fromSeq, e);
    }
  }

  /**
   * 按事件表里实际存在的行投影，中间没有落库的序号直接跳过。
   * <p>
   * 热路径 Lua 的 {@code INCR} 可能已经加过序号，但 Persist 没写成事件。这时表里会出现
   * 1 然后 5。这里把 {@code applied_seq} 推到 5，而不是反复缓冲 5 号事件。
   */
  private void applyStored(String promotionId, long fromSeq) {
    List<EventEntity> events = eventRepository.findByPromotionIdAndSeqGreaterThanEqualOrderBySeqAsc(promotionId,
        fromSeq);
    for (EventEntity entity : events) {
      applyIfNew(new EventMessageDto(entity.getEventId(), entity.getPromotionId(), entity.getSeq(), entity.getType(),
          entity.getOccurredAt(), entity.getContent(), entity.getCustomerId()));
    }
  }

  /**
   * 序号尚未应用时写入读模型，并推进 {@code applied_seq}。已经应用过的行直接跳过。
   */
  private void applyIfNew(EventMessageDto message) {
    long applied = store.appliedSeq(message.getPromotionId());
    if (message.getSeq() != 0 && message.getSeq() <= applied) {
      return;
    }
    apply(message);
    if (message.getSeq() != 0) {
      store.setAppliedSeq(message.getPromotionId(), message.getSeq());
    }
  }

  /**
   * 把先前因乱序放进 Redis 缓冲的消息再投影一遍。
   *
   * @param promotionId 要清空缓冲的活动
   */
  private void drain(String promotionId) {
    for (EventMessageDto buffered : store.drainBuffer(promotionId)) {
      project(buffered);
    }
  }

  /**
   * 按事件类型更新读模型。未识别的类型什么都不写。
   * <p>
   * {@code (PromotionStartEvent) event} 是强制类型转换：前面已经用类型常量判断过，这里才能取出活动对象。
   * {@code @SuppressWarnings("unchecked")} 关掉「泛型转换未检查」的编译警告，顾客编号在本服务里是 {@code String}。
   *
   * @param message 已经通过序号检查、准备落地的一条事件
   */
  private void apply(EventMessageDto message) {
    SecKillEvent event = eventFormat.fromMessage(message);
    // 活动开始：Redis 出现在「进行中」列表，搜索索引也写入这场活动。
    if (SecKillEventType.PromotionStartEvent.equals(event.getType())) {
      PromotionEntity promotion = ((PromotionStartEvent) event).getPromotion();
      store.saveActivePromotion(promotion);
      searchIndex.indexPromotion(promotion);
    } else if (SecKillEventType.CouponGrabbedEvent.equals(event.getType())) {
      // 有人抢到：券写入 Redis 读模型，再用保存后的对象更新 Elasticsearch。Query 此后才能看见。
      @SuppressWarnings("unchecked")
      CouponEntity<String> coupon = ((CouponGrabbedEvent<String>) event).getCoupon();
      CouponEntity<String> saved = store.saveCoupon(coupon);
      searchIndex.indexCoupon(saved);
    } else if (SecKillEventType.PromotionFinishEvent.equals(event.getType())) {
      // 卖完或到点结束：进行中列表去掉它，搜索索引标成已结束。
      store.removeActivePromotion(event.getPromotionId());
      searchIndex.markPromotionFinished(event.getPromotionId());
    }
  }
}
