package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.es.InMemorySearchIndex;
import io.servicecomb.poc.demo.seckill.event.CouponGrabbedEvent;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat;
import io.servicecomb.poc.demo.seckill.redis.InMemorySecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringSecKillEventRepository;
import java.util.Collections;
import java.util.Date;
import java.util.UUID;
import org.junit.Test;

/**
 * 守护事件投影必须按序号来：序号 2 先到时先攒着，序号 1 补上后再一起写入读模型。
 * <p>
 * 读模型是 {@link InMemorySecKillStore}，搜索索引用 {@link InMemorySearchIndex}，都在内存里。
 * 事件仓库是 Mockito 假对象，按序号补洞时返回空列表。没有 Kafka，也没有 H2。
 */
public class EventProjectorOrderingTest {

  /**
   * 前置：读模型和搜索索引都是空的内存实现。
   * 动作：先投影序号 2（顾客 b），再投影序号 1（顾客 a）。
   * 期望：只有序号 2 时 b 的券还是 0 张；序号 1 到达后 a 和 b 各有 1 张。
   */
  @Test
  public void appliesBufferedEventAfterGapIsFilled() {
    InMemorySecKillStore store = new InMemorySecKillStore();
    SpringSecKillEventRepository repository = mock(SpringSecKillEventRepository.class);
    when(repository.findByPromotionIdAndSeqGreaterThanEqualOrderBySeqAsc(anyString(), anyLong()))
        .thenReturn(Collections.<io.servicecomb.poc.demo.seckill.entities.EventEntity>emptyList());
    EventProjector projector = new EventProjector(new SecKillEventFormat(new JacksonGeneralFormat()), store,
        new InMemorySearchIndex(), repository);
    PromotionEntity promotion = new PromotionEntity(new Date(), 5, 0.8f);
    SecKillEventFormat format = new SecKillEventFormat(new JacksonGeneralFormat());

    EventMessageDto second = format.toMessage(new CouponGrabbedEvent<String>(promotion, "b"),
        UUID.randomUUID().toString(), 2);
    EventMessageDto first = format.toMessage(new CouponGrabbedEvent<String>(promotion, "a"),
        UUID.randomUUID().toString(), 1);

    projector.project(second);
    assertThat(store.customerCoupons("b").size(), is(0));

    projector.project(first);
    assertThat(store.customerCoupons("a").size(), is(1));
    assertThat(store.customerCoupons("b").size(), is(1));
  }

  /**
   * 前置：事件表只有序号 5，没有 2、3、4（热路径 INCR 过，但中间几次没落库）。
   * 动作：投影序号 5。
   * 期望：从事件表直接应用 5，读模型有这张券，不会把同一条消息缓冲成千上万次。
   */
  @Test
  public void skipsMissingSeqWhenEventTableHasLaterRow() {
    InMemorySecKillStore store = new InMemorySecKillStore();
    SpringSecKillEventRepository repository = mock(SpringSecKillEventRepository.class);
    EventProjector projector = new EventProjector(new SecKillEventFormat(new JacksonGeneralFormat()), store,
        new InMemorySearchIndex(), repository);
    PromotionEntity promotion = new PromotionEntity(new Date(), 5, 0.8f);
    SecKillEventFormat format = new SecKillEventFormat(new JacksonGeneralFormat());
    EventMessageDto fifth = format.toMessage(new CouponGrabbedEvent<String>(promotion, "c"),
        UUID.randomUUID().toString(), 5);
    when(repository.findByPromotionIdAndSeqGreaterThanEqualOrderBySeqAsc(anyString(), anyLong()))
        .thenReturn(Collections.singletonList(new io.servicecomb.poc.demo.seckill.entities.EventEntity(
            fifth.getEventId(), fifth.getSeq(), fifth.getType(), fifth.getPromotionId(), fifth.getCustomerId(),
            fifth.getContent(), fifth.getOccurredAt())));

    projector.project(fifth);

    assertThat(store.customerCoupons("c").size(), is(1));
    assertThat(store.appliedSeq(promotion.getPromotionId()), is(5L));
  }
}
