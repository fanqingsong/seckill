/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ OutboxRelayTest.java                            │
 * │ 场景：轮询能把未发布 outbox 发到 Kafka          │
 * └─────────────────────────────────────────────────┘
 *
 * 构造未发布 outbox 行
 * │
 * ▼
 * 【本文件】调用 pollPending
 * │
 * └──▶ 发布器收到 key/payload，行变已发布
 *
 * 一句话：不启 CDC，只测共享发布逻辑。
 */

package io.servicecomb.poc.demo.seckill.relay;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import io.servicecomb.poc.demo.seckill.kafka.SecKillEventPublisher;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringOutboxRepository;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * 替代真实 Kafka 与 PostgreSQL：用 Mockito 桩仓库和发布器，守护 {@link OutboxRowPublisher} 的投递顺序。
 */
@RunWith(MockitoJUnitRunner.class)
public class OutboxRelayTest {

  @Mock
  private SpringOutboxRepository outboxRepository;

  @Mock
  private SecKillEventPublisher publisher;

  private OutboxRowPublisher rowPublisher;

  @Before
  public void setUp() {
    rowPublisher = new OutboxRowPublisher(outboxRepository, publisher);
  }

  /**
   * 前置：一条未发布 outbox。动作：批量轮询。期望：Kafka 收到 payload，实体被标已发布并 save。
   */
  @Test
  public void pollPublishesUnpublishedRow() {
    OutboxEntity row = new OutboxEntity("evt-1", "promo-1", "{\"type\":\"start\"}");
    when(outboxRepository.findTop50ByPublishedFalseOrderByIdAsc()).thenReturn(List.of(row));

    rowPublisher.publishPendingBatch();

    verify(publisher).publish(eq("promo-1"), eq("{\"type\":\"start\"}"));
    assertThat(row.isPublished(), is(true));
    ArgumentCaptor<OutboxEntity> saved = ArgumentCaptor.forClass(OutboxEntity.class);
    verify(outboxRepository).save(saved.capture());
    assertThat(saved.getValue().isPublished(), is(true));
  }
}
