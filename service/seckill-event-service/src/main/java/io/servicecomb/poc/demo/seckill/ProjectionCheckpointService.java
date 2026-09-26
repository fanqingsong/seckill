/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ ProjectionCheckpointService.java                │
 * │ 链路：投影 · PostgreSQL 进度                     │
 * └─────────────────────────────────────────────────┘
 *
 *   EventProjector 推进 applied_seq
 *      │
 *      ▼
 *   【本文件】upsert projection_checkpoint
 *      │
 *      ▼
 *   PostgreSQL（与事件表同库，ddl-auto=update 建表）
 *
 * 一句话：Redis 序号丢了但读模型还在时，靠这里恢复进度。
 */

package io.servicecomb.poc.demo.seckill;

import io.servicecomb.poc.demo.seckill.entities.ProjectionCheckpointEntity;
import io.servicecomb.poc.demo.seckill.repositories.spring.SpringProjectionCheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 Event 投影进度写入 PostgreSQL，与 Redis {@code seckill:applied_seq} 保持一致方向。
 * <p>
 * {@code @Service} 让 Spring 扫描并注入；{@code @Transactional} 包住单次 upsert，
 * 和事件表不在同一事务里（投影本身不写事件表），失败只影响进度备份，不影响 Kafka 消费逻辑。
 */
@Service
public class ProjectionCheckpointService {

  private final SpringProjectionCheckpointRepository repository;

  /**
   * @param repository JPA 访问 {@code projection_checkpoint} 表
   */
  public ProjectionCheckpointService(SpringProjectionCheckpointRepository repository) {
    this.repository = repository;
  }

  /**
   * 投影成功且序号大于 0 时调用。序号 0 的事件不参与缺口判断，也不写入本表。
   *
   * @param promotionId 活动编号
   * @param seq 刚写入读模型的序号
   */
  @Transactional
  public void recordAppliedSeq(String promotionId, long seq) {
    if (seq <= 0) {
      return;
    }
    ProjectionCheckpointEntity row = repository.findById(promotionId)
        .orElseGet(() -> new ProjectionCheckpointEntity(promotionId, 0L));
    row.advanceTo(seq);
    repository.save(row);
  }

  /**
   * 读取 PostgreSQL 里记录的最大已投影序号。
   *
   * @param promotionId 活动编号
   * @return 没有记录时 0
   */
  public long lastAppliedSeq(String promotionId) {
    return repository.findById(promotionId).map(ProjectionCheckpointEntity::getLastAppliedSeq).orElse(0L);
  }
}
