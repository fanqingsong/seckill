/*
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ ProjectionCheckpointEntity.java                        │
 * │ 链路：读模型投影进度（PostgreSQL）                     │
 * └──────────────────────────────────────────────────────┘
 *
 *   EventProjector 按序应用 Kafka / 回放事件
 *      │
 *      ▼
 *   【本文件】projection_checkpoint 一行
 *      │
 *      ├── Redis 仍存 seckill:applied_seq（热路径判断）
 *      └── Redis 丢序号键但 AOF 恢复读模型时，启动可据此对齐
 *
 * 一句话：账本仍是 sec_kill_event；本表只记「已投影到哪一号」，便于增量回放与恢复。
 */

package io.servicecomb.poc.demo.seckill.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 每个活动一条投影进度，落在 PostgreSQL 表 {@code projection_checkpoint}。
 * <p>
 * Event 服务在 Redis 读模型里成功应用一条带序号的事件后，除了写
 * {@code seckill:applied_seq:{promotionId}}，也会更新本表。这样 Redis 只丢了序号键、
 * 但 AOF/RDB 把券和活动读模型找回来时，启动逻辑可以把 Redis 序号与这里对齐，避免从 0 全量重放。
 * 若读模型也空了，不能只靠本表跳过重放，必须 {@code fromSeq=0} 或显式全量回放。
 * <p>
 * {@code @Entity} 表示 JPA 实体；{@code @Id} 在 {@link #promotionId} 上，一场活动一行。
 */
@Entity
@Table(name = "projection_checkpoint")
public class ProjectionCheckpointEntity {

  /** 活动编号，与事件表、Kafka key 一致。 */
  @Id
  @Column(length = 64)
  private String promotionId;

  /**
   * 已经按序投影到读模型（Redis + Elasticsearch）的最大事件序号。
   * 与 Redis {@code seckill:applied_seq} 语义相同，只是落在 PostgreSQL 里做 durable 备份。
   */
  private long lastAppliedSeq;

  /** JPA 需要无参构造器；业务代码用带参构造器创建新行。 */
  protected ProjectionCheckpointEntity() {
  }

  /**
   * @param promotionId 活动编号
   * @param lastAppliedSeq 刚投影成功的序号
   */
  public ProjectionCheckpointEntity(String promotionId, long lastAppliedSeq) {
    this.promotionId = promotionId;
    this.lastAppliedSeq = lastAppliedSeq;
  }

  public String getPromotionId() {
    return promotionId;
  }

  public long getLastAppliedSeq() {
    return lastAppliedSeq;
  }

  /** 仅当新序号更大时更新，避免乱序或重复写把进度往回拉。 */
  public void advanceTo(long seq) {
    if (seq > lastAppliedSeq) {
      lastAppliedSeq = seq;
    }
  }
}
