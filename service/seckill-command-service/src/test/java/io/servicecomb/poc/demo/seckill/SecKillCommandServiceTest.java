package io.servicecomb.poc.demo.seckill;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import io.servicecomb.poc.demo.seckill.event.SecKillEventFormat;
import io.servicecomb.poc.demo.seckill.json.JacksonGeneralFormat;
import io.servicecomb.poc.demo.seckill.redis.InMemorySecKillStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;

/**
 * 守护抢券热路径上的写服务：库存够就成功，卖完失败，同一顾客不能抢第二次。
 * <p>
 * 库存用的是 {@link InMemorySecKillStore}，不连接真实 Redis。
 * {@link TransactionalEventOutboxWriter} 被 Mockito 替掉，本类不会写数据库，也不会发 Kafka。
 * 没有 Elasticsearch，也没有 H2。
 */
public class SecKillCommandServiceTest {

  private final int numberOfCoupons = 10;
  private final InMemorySecKillStore store = new InMemorySecKillStore();
  private final PromotionEntity promotion = new PromotionEntity(new Date(), numberOfCoupons, 0.7f);
  private final TransactionalEventOutboxWriter writer = mock(TransactionalEventOutboxWriter.class);
  private final SecKillCommandService<Integer> commandService = new SecKillCommandService<Integer>(promotion, store,
      writer, new SecKillEventFormat(new JacksonGeneralFormat()), false);

  private final AtomicInteger customerIdGenerator = new AtomicInteger();
  private final AtomicInteger numberOfSuccess = new AtomicInteger();

  @Before
  public void setUp() {
    doNothing().when(writer).persist(any(EventMessageDto.class));
    store.initStock(promotion.getPromotionId(), numberOfCoupons, Collections.<String>emptySet(), 0);
  }

  /**
   * 前置：库存 10，outbox 写入被桩成空操作。
   * 动作：5 个不同顾客依次抢券。
   * 期望：五次都是 Success，并且一次都没有调用 writer.persist。
   */
  @Test
  public void acceptsCustomersUntilStockIsGone() {
    for (int i = 0; i < 5; i++) {
      assertThat(commandService.addCouponTo(i), is(SecKillGrabResult.Success));
    }
    verify(writer, never()).persist(any(EventMessageDto.class));
  }

  /**
   * 前置：库存只有 10 张。
   * 动作：200 个线程在屏障打开后同时抢，顾客编号各不相同。
   * 期望：成功次数正好是 10，不会超卖。
   */
  @Test
  public void concurrentGrabsDoNotOversell() {
    int threads = 200;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    addCouponsAsync(threads, () -> {
      try {
        barrier.await();
        return commandService.addCouponTo(customerIdGenerator.incrementAndGet()) == SecKillGrabResult.Success;
      } catch (InterruptedException | BrokenBarrierException e) {
        throw new RuntimeException(e);
      }
    }, success -> {
      if (success) {
        numberOfSuccess.incrementAndGet();
      }
    });
    assertThat(numberOfSuccess.get(), is(10));
  }

  /**
   * 前置：库存 10。
   * 动作：先让 10 个顾客各抢一张，再让第 11 个顾客抢。
   * 期望：前 10 次 Success，最后一次 Failed。
   */
  @Test
  public void failsWhenSoldOut() {
    for (int i = 0; i < numberOfCoupons; i++) {
      assertThat(commandService.addCouponTo(i), is(SecKillGrabResult.Success));
    }
    assertThat(commandService.addCouponTo(100), is(SecKillGrabResult.Failed));
  }

  /**
   * 前置：顾客 1 还没抢过。
   * 动作：同一顾客连续抢两次。
   * 期望：第一次 Success，第二次 Duplicate，库存不会被扣两次。
   */
  @Test
  public void failsDuplicateAddCustomer() {
    assertThat(commandService.addCouponTo(1), is(SecKillGrabResult.Success));
    assertThat(commandService.addCouponTo(1), is(SecKillGrabResult.Duplicate));
  }

  private void addCouponsAsync(int threads, Supplier<Boolean> supplier, Consumer<Boolean> consumer) {
    ExecutorService executorService = Executors.newFixedThreadPool(threads);
    List<CompletableFuture<Void>> futures = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < threads; i++) {
      futures.add(CompletableFuture.supplyAsync(supplier, executorService).thenAccept(consumer));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).join();
    executorService.shutdown();
  }
}
