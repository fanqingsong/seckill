package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
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

public class EventProjectorOrderingTest {

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
}
