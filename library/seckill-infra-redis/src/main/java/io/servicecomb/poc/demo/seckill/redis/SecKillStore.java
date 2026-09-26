/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ SecKillStore.java                             │
 * │ 链路：抢券与查询 · 契约                       │
 * └───────────────────────────────────────────────┘
 *
 * Command / Event / Query
 *          │
 *          ▼
 *      【本文件】
 *          ├─ 热路径 Lua 库存
 *          ├─ grab stream
 *          └─ 查询读模型
 *
 * 一句话：热路径、抢券队列和查询读模型都走这张契约。
 */

package io.servicecomb.poc.demo.seckill.redis;

import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Redis 上的秒杀存储契约，同时覆盖热路径库存、抢券队列和查询读模型。
 * <p>
 * Command 到 {@code publishTime} 才调用 {@link #initStock}。抢券 HTTP 只调用 {@link #tryGrab}：
 * 一次调用里完成「是否已抢、库存是否够、扣减、把令牌放进队列」。成功只表示库存已扣，
 * PostgreSQL 里还没有这张券。Persist 再调用 {@link #pollInflight} 把令牌写成事件。
 * Event 服务消费 Kafka {@code seckill.events} 之后，用本接口后半段把活动和券投影成读模型。
 * Query 的活动列表和「我的券」读这些读模型；搜索不走本接口，而走 Elasticsearch。
 * <p>
 * {@code seckill.infra.mode} 等于 {@code prod} 时实现是 {@link JedisSecKillStore}，
 * 其它值（配置缺省为 {@code memory}）是 {@link InMemorySecKillStore}。调用方只依赖本接口。
 */
public interface SecKillStore {

  /**
   * 为一次活动准备热路径库存。
   * <p>
   * Command 在活动发布时刻调用。调用前 Redis 里可以还没有库存键，抢券会得到「尚未开始」。
   * 本方法不写 PostgreSQL，也不发 Kafka。
   *
   * @param promotionId 活动编号，后面所有热路径键都带上它
   * @param remaining 此刻还可卖的张数。恢复时是事件回放算出来的剩余，不是创建活动时的原始总量
   * @param claimedCustomers 已经抢到的顾客。用于恢复已抢集合，避免同一人再扣一次库存。可以为 null
   * @param lastSeq 已经分配过的最大事件序号。下一次抢券或结束事件从它后面继续编号
   */
  void initStock(String promotionId, int remaining, Set<String> claimedCustomers, long lastSeq);

  /**
   * 热路径上为一名顾客抢一张券。
   * <p>
   * 生产实现用一段 Lua 一次做完判断和扣减，HTTP 线程不写 PostgreSQL、不发 Kafka。
   * 成功时令牌进入抢券队列，等 Persist 落库。
   *
   * @param promotionId 活动编号
   * @param customerId 顾客编号。同一活动里再次提交应得到「已经抢过」，而不是再扣库存
   * @return 成功、重复、卖完或尚未开始。成功时带上本次序号和扣减后的剩余张数
   */
  GrabAttempt tryGrab(String promotionId, String customerId);

  /**
   * 把一次已经扣掉的库存加回去，并把该顾客移出已抢集合。
   * <p>
   * 不删除抢券队列里可能已经写下的令牌，也不改 PostgreSQL。当前仓库里的抢券路径没有调用它。
   *
   * @param promotionId 活动编号
   * @param customerId 要撤销的顾客编号
   */
  void compensateGrab(String promotionId, String customerId);

  /**
   * 从抢券队列取出一条尚未确认的令牌，交给 Persist 落库。
   * <p>
   * 取到之后令牌仍算「处理中」，直到 {@link #ackGrab}。进程崩溃后同一条还能再被读到。
   *
   * @param timeoutMillis 队列里没有可处理的新令牌时最多等待的毫秒数。0 表示不等待
   * @return 令牌；这一轮没有消息时返回 null。内存实现在等待被中断时也返回 null
   */
  GrabToken pollInflight(long timeoutMillis);

  /**
   * 确认一条令牌已经写入 PostgreSQL（或确认是重复顾客），把它从处理中队列去掉。
   * <p>
   * 不改库存。令牌为 null 时什么都不做。
   *
   * @param token {@link #pollInflight} 返回的那一条
   */
  void ackGrab(GrabToken token);

  /**
   * 把一条还不能落库的令牌放回队列末尾，稍后再读。
   * <p>
   * Persist 在 PostgreSQL 里还找不到活动行时调用。不改库存。令牌为 null 时什么都不做。
   *
   * @param token 需要推迟的令牌
   */
  void deferInflight(GrabToken token);

  /**
   * 抢券队列里还没确认的条数，包含尚未投递的和正在处理的。
   * <p>
   * Command 用它判断「到了结束时间，并且队列已经排空」之后才写结束事件。不改任何数据。
   *
   * @return 还没 ack 的令牌数。队列或消费者组还不存在时，Jedis 实现返回 0
   */
  int pendingGrabCount();

  /**
   * 热路径库存键是否已经写入。
   * <p>
   * Command 用它避免在 {@code publishTime} 之前或重复启动时再次初始化库存。
   *
   * @param promotionId 活动编号
   * @return 库存键存在时为 true。存在只说明已经初始化，不表示还剩多少张
   */
  boolean stockKeysPresent(String promotionId);

  /**
   * 为活动分配下一个事件序号。
   * <p>
   * 抢券成功的序号由 {@link #tryGrab} 在同一次扣减里分配。本方法给开始事件、结束事件使用，
   * 让它们和抢券事件共用一条递增序号。不扣库存。
   *
   * @param promotionId 活动编号
   * @return 递增之后的序号，从已有值加 1；该活动还没有序号时从 1 开始
   */
  long nextSeq(String promotionId);

  /**
   * 读出尚未结束的活动，供查询页的活动列表使用。
   * <p>
   * 数据来自读模型，不是扫 PostgreSQL 事件表。Event 服务在投影 {@code PromotionStartEvent} 时写入，
   * 投影 {@code PromotionFinishEvent} 时移除。
   *
   * @return 当前仍在读模型里的活动。没有活动时为空集合，不返回 null
   */
  Collection<PromotionEntity> activePromotions();

  /**
   * 读出一名顾客已经抢到的券，供查询页「我的券」使用。
   * <p>
   * 这是 Redis 读模型。刚抢成功、Persist 和 Event 还没投影完时，这里可以暂时没有这张券。
   *
   * @param customerId 顾客编号
   * @return 该顾客的券。没有时为空集合
   */
  Collection<CouponEntity<String>> customerCoupons(String customerId);

  /**
   * 按券编号取出比 {@code latestId} 更新的券，供查询页增量拉取。
   * <p>
   * 比较的是读模型里的券 id，不是事件序号。不包含编号恰好等于 {@code latestId} 的那一张。
   *
   * @param latestId 调用方已经见过的最大券编号
   * @return 编号更大的券，内存实现会按 id 从小到大排好
   */
  Collection<CouponEntity<String>> couponsAfter(int latestId);

  /**
   * 把活动放进「进行中」读模型。
   * <p>
   * Event 服务投影 {@code PromotionStartEvent} 时调用。不改热路径库存。
   *
   * @param promotion 要展示的活动。同一活动编号再次写入会覆盖上一份
   */
  void saveActivePromotion(PromotionEntity promotion);

  /**
   * 从「进行中」读模型里去掉活动。
   * <p>
   * Event 服务投影 {@code PromotionFinishEvent} 时调用。已经发出的券仍留在券读模型里。
   *
   * @param promotionId 结束的活动编号
   */
  void removeActivePromotion(String promotionId);

  /**
   * 把一张券写入读模型。同一活动、同一顾客已有记录时不再分配新编号。
   * <p>
   * Event 服务投影 {@code CouponGrabbedEvent} 时调用。不扣库存。
   *
   * @param coupon 待保存的券。新券的 id 由本方法填上
   * @return 实际留在读模型里的那一张。重复时返回已存在的券，而不是参数里的对象
   */
  CouponEntity<String> saveCoupon(CouponEntity<String> coupon);

  /**
   * 读出某个活动已经投影到读模型的最大事件序号。
   * <p>
   * Event 服务用它判断 Kafka 消息是过期、正好下一条，还是中间有缺口。不改数据。
   *
   * @param promotionId 活动编号
   * @return 已投影序号。还没有投影过时为 0
   */
  long appliedSeq(String promotionId);

  /**
   * 记下某个活动已经投影完成的事件序号。
   *
   * @param promotionId 活动编号
   * @param seq 刚刚按顺序投影成功的序号
   */
  void setAppliedSeq(String promotionId, long seq);

  /**
   * 判断 Redis 里是否还留有该活动的读模型痕迹（进行中活动、序号键或至少一张券）。
   * <p>
   * Event 服务启动时用：只有为真时才允许用 PostgreSQL checkpoint 把 {@link #appliedSeq} 往前补，
   * 避免读模型已空却误以为投影已完成。
   *
   * @param promotionId 活动编号
   * @return 读模型似乎仍在 Redis 时为 true
   */
  boolean hasReadModelForPromotion(String promotionId);

  /**
   * 把一条暂时还不能投影的事件放进该活动的缓冲区。
   * <p>
   * 序号跳过了下一号（中间有缺口）时，Event 服务先放这里，等缺的序号补上再取出。
   * 不改库存，也不写 Elasticsearch。
   *
   * @param event 还不能按序投影的消息，里面的活动编号决定进哪一个缓冲区
   */
  void buffer(io.servicecomb.poc.demo.seckill.dto.EventMessageDto event);

  /**
   * 取出并清空某个活动缓冲区里的事件，让 Event 服务再试着按序投影。
   *
   * @param promotionId 活动编号
   * @return 这一轮取出的消息。缓冲区不存在时为空列表，不返回 null
   */
  List<io.servicecomb.poc.demo.seckill.dto.EventMessageDto> drainBuffer(String promotionId);

  /**
   * 在券读模型里按顾客和活动过滤，并包装成抢券事件消息。
   * <p>
   * 查询页的搜索不调用本方法，搜索走 Elasticsearch。本方法留在契约里，供直接读 Redis 读模型的调用使用。
   *
   * @param customerId 顾客编号。Jedis 实现在它为 null 或空串时直接返回空列表
   * @param promotionId 活动编号。null 或空串表示不按活动过滤
   * @return 包成 {@code CouponGrabbedEvent} 的消息。具体 payload 以各实现为准
   */
  List<io.servicecomb.poc.demo.seckill.dto.EventMessageDto> searchCoupons(String customerId, String promotionId);
}
