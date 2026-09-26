/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ ReadModelCheckpointBootstrap.java               │
 * │ 链路：投影 · 启动对齐 Redis 序号                 │
 * └─────────────────────────────────────────────────┘
 *
 *   Event 服务进程启动
 *      │
 *      ▼
 *   【本文件】读 projection_checkpoint + 探测读模型
 *      │
 *      ▼
 *   若 Redis 序号落后且读模型键仍在 ──▶ setAppliedSeq 对齐
 *
 * 一句话：AOF 找回券数据但 applied_seq 键缺失时，避免误从 0 全量 replay。
 */

package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.entities.ProjectionCheckpointEntity;
import io.servicecomb.poc.demo.seckill.redis.SecKillStore;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringProjectionCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Event 服务启动后执行一次：在「读模型数据似乎还在 Redis」的前提下，用 PostgreSQL  checkpoint
 * 把 {@link SecKillStore#appliedSeq} 往前推，避免序号键丢失却要从 0 重放全部抢券事件。
 * <p>
 * {@link ApplicationRunner} 表示 Spring Boot 启动完成、容器就绪后会调用 {@link #run}。
 * 若 Redis 卷也空了、读模型不存在，本类不会把序号硬推到 checkpoint，避免 Kafka 不再投递旧消息却以为已经投影完。
 */
@Component
public class ReadModelCheckpointBootstrap implements ApplicationRunner {

  private static final Logger logger = LoggerFactory.getLogger(ReadModelCheckpointBootstrap.class);

  private final SpringProjectionCheckpointRepository checkpointRepository;
  private final SecKillStore store;

  /**
   * @param checkpointRepository PostgreSQL 里的投影进度
   * @param store Redis 读模型与 applied_seq
   */
  public ReadModelCheckpointBootstrap(SpringProjectionCheckpointRepository checkpointRepository, SecKillStore store) {
    this.checkpointRepository = checkpointRepository;
    this.store = store;
  }

  /**
   * 对每个有 checkpoint 的活动：仅当 {@link SecKillStore#hasReadModelForPromotion} 为真且
   * Redis 序号低于 PostgreSQL 记录时，把 Redis 序号补到 checkpoint。
   */
  @Override
  public void run(ApplicationArguments args) {
    for (ProjectionCheckpointEntity checkpoint : checkpointRepository.findAll()) {
      String promotionId = checkpoint.getPromotionId();
      long pgSeq = checkpoint.getLastAppliedSeq();
      long redisSeq = store.appliedSeq(promotionId);
      if (pgSeq > redisSeq && store.hasReadModelForPromotion(promotionId)) {
        store.setAppliedSeq(promotionId, pgSeq);
        logger.info("Aligned Redis applied_seq for promotion {} from {} to {} (PostgreSQL checkpoint)",
            promotionId, redisSeq, pgSeq);
      }
    }
  }
}
