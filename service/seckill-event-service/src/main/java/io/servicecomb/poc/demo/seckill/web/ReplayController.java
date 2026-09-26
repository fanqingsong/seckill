/*
 * ┌─ 文件 ──────────────────────────────────────────┐
 * │ ReplayController.java                           │
 * │ 链路：投影 · 回放 HTTP                          │
 * └─────────────────────────────────────────────────┘
 *
 * 直接访问 Event 服务 8084（不经浏览器 nginx）
 * │
 * ▼
 * 【本文件】POST /admin/replay
 * │
 * ▼
 * PostgreSQL 事件表 → 再写入 Redis 读模型与 Elasticsearch
 *
 * 一句话：nginx 把 /admin 转到 Admin 服务，到不了这个回放接口。
 */

package io.servicecomb.poc.demo.seckill.web;

import io.servicecomb.poc.demo.seckill.EventProjector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 按活动把 PostgreSQL 里已有的事件重新投影到 Redis 读模型和 Elasticsearch。
 * <p>
 * {@code @RestController} 表示返回值直接写成响应体。{@code @RequestMapping("/admin")} 加上
 * {@code @PostMapping("/replay")}，完整路径是本进程上的 {@code POST /admin/replay}。
 * 这个接口不经过浏览器的 nginx 代理：nginx 把 {@code /admin} 转到 Gateway，Gateway 再转到 Admin 服务，
 * 到不了这里。回放要直接访问 Event 服务。
 * <p>
 * 构造器参数由 Spring 注入，效果和 {@code @Autowired} 构造器一样：容器里只有一个 {@link EventProjector} 时会自动传入。
 */
@RestController
@RequestMapping("/admin")
public class ReplayController {

  private final EventProjector projector;

  /**
   * @param projector 真正读事件表并写读模型的投影器
   */
  public ReplayController(EventProjector projector) {
    this.projector = projector;
  }

  /**
   * 从指定序号起重放一场活动的事件。
   * <p>
   * {@code @RequestParam} 表示参数来自查询字符串，例如 {@code ?promotionId=...&fromSeq=0}。
   * {@code defaultValue = "0"} 表示没传 {@code fromSeq} 时从 0 开始（全量重建读模型）。
   * {@code incremental=true} 时从 Redis 与 PostgreSQL checkpoint 的较大值加 1 起播，适合读模型仍在、只需补尾巴。
   * 本方法不写新的业务事件，只触发投影。
   *
   * @param promotionId 要重放的活动编号
   * @param fromSeq 起始序号，包含这一条；与 {@code incremental} 同时传时以 {@code fromSeq} 为准
   * @param incremental 为 true 且未显式关心 fromSeq 时，用增量起点代替 0
   * @return 固定正文 {@code replayed}，表示方法已返回；投影写入的是 Redis 和 Elasticsearch
   */
  @PostMapping("/replay")
  public String replay(@RequestParam String promotionId, @RequestParam(defaultValue = "0") long fromSeq,
      @RequestParam(defaultValue = "false") boolean incremental) {
    long startSeq = projector.resolveReplayStartSeq(promotionId, fromSeq, incremental);
    projector.replay(promotionId, startSeq);
    return "replayed";
  }
}
