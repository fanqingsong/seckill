/*
 * ┌─ 文件 ───────────────────────────────────────────────┐
 * │ SpringProjectionCheckpointRepository.java              │
 * │ 链路：读模型投影进度                                   │
 * └──────────────────────────────────────────────────────┘
 *
 *   EventProjector.applyIfNew
 *      │
 *      ▼
 *   【本文件】读写 projection_checkpoint
 *      │
 *      ▼
 *   PostgreSQL（与 sec_kill_event 同库）
 *
 * 一句话：Redis applied_seq 的 durable 副本，供恢复与增量回放。
 */

package io.servicecomb.poc.demo.seckill.repositories.spring;

import io.servicecomb.poc.demo.seckill.entities.ProjectionCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA 仓库：按活动编号读写 {@link ProjectionCheckpointEntity}。
 * <p>
 * Event 服务注入本接口，在投影成功时 {@code save}，启动时 {@code findAll} 与 Redis 对齐。
 * 抢券 Command / Persist 不调用本接口。
 */
public interface SpringProjectionCheckpointRepository extends JpaRepository<ProjectionCheckpointEntity, String> {
}
