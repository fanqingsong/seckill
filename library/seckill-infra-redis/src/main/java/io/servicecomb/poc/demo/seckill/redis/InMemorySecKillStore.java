package io.servicecomb.poc.demo.seckill.redis;

import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * {@link SecKillStore} 的进程内实现，给 {@code seckill.infra.mode} 不是 {@code prod} 时使用（缺省 {@code memory}）。
 * <p>
 * 字段分成三块，对应 Jedis 实现里的三类 Redis 结构，这样测试不用启动 Redis，调用方仍走同一套方法：
 * 热路径库存（剩余张数、已抢顾客、事件序号）、抢券队列（待处理和处理中）、查询读模型（活动、券、投影序号和乱序缓冲）。
 * 抢券在 {@link #tryGrab} 里一次做完判断和扣减，成功后只把令牌放进内存队列，不写 PostgreSQL，也不发 Kafka。
 */
public class InMemorySecKillStore implements SecKillStore {

  /** 热路径库存：活动编号 → 剩余张数。对应 Redis 字符串 {@code seckill:stock:{promotionId}}。键不存在表示尚未开始。 */
  private final Map<String, AtomicLong> stocks = new ConcurrentHashMap<String, AtomicLong>();
  /** 热路径已抢顾客。对应 Redis 集合 {@code seckill:claimed:{promotionId}}。同一顾客只能成功进入一次。 */
  private final Map<String, Set<String>> claimed = new ConcurrentHashMap<String, Set<String>>();
  /** 热路径事件序号。对应 Redis 字符串 {@code seckill:seq:{promotionId}}，抢券和结束事件共用。 */
  private final Map<String, AtomicLong> seqs = new ConcurrentHashMap<String, AtomicLong>();
  /** 查询读模型：尚未结束的活动。对应 Redis 哈希 {@code seckill:active_promotions}。 */
  private final Map<String, PromotionEntity> promotions = new ConcurrentHashMap<String, PromotionEntity>();
  /** 查询读模型：券。键是 {@code promotionId:customerId}，对应 Redis 字符串 {@code seckill:coupon:} 加这个键。 */
  private final Map<String, CouponEntity<String>> couponsByKey = new ConcurrentHashMap<String, CouponEntity<String>>();
  /** 投影进度：活动编号 → 已经按序应用到读模型的最大序号。对应 {@code seckill:applied_seq:{promotionId}}。 */
  private final Map<String, AtomicLong> applied = new ConcurrentHashMap<String, AtomicLong>();
  /** 乱序缓冲：序号跳号时先放这里。对应 Redis 列表 {@code seckill:buffer:{promotionId}}。 */
  private final Map<String, List<EventMessageDto>> buffers = new ConcurrentHashMap<String, List<EventMessageDto>>();
  /** 券读模型的自增编号。对应 Redis {@code seckill:coupon_id}。 */
  private final AtomicInteger couponId = new AtomicInteger();
  /**
   * 抢券队列里还没被 Persist 取走的令牌。
   * 对应 Redis Stream {@code seckill:grabs} 里尚未投递给消费者组 {@code persist} 的条目。
   */
  private final LinkedBlockingDeque<GrabToken> pendingGrabs = new LinkedBlockingDeque<GrabToken>();
  /**
   * 已经交给 Persist、尚未 ack 的令牌。队头会在 ack 之前被反复读到，用来模拟处理中的消息。
   * 对应 Stream 消费者组里 pending 的条目。
   */
  private final ConcurrentLinkedQueue<GrabToken> inflightGrabs = new ConcurrentLinkedQueue<GrabToken>();
  /** 保护「待处理」和「处理中」两段队列的交接，避免两条线程拿到同一张新令牌。 */
  private final Object grabQueueLock = new Object();

  /**
   * 写入热路径的三份数据：剩余张数、已抢顾客、当前序号。
   * <p>
   * 对应 Jedis 的 SET 库存、重建已抢集合、SET 序号。不写读模型，也不发消息。
   *
   * @param promotionId 活动编号
   * @param remaining 还可卖的张数
   * @param claimedCustomers 已经抢到的顾客。null 表示还没有人抢到
   * @param lastSeq 已经用过的最大序号。下一次 {@link #tryGrab} 或 {@link #nextSeq} 从它后面递增
   */
  @Override
  public void initStock(String promotionId, int remaining, Set<String> claimedCustomers, long lastSeq) {
    stocks.put(promotionId, new AtomicLong(remaining));
    Set<String> set = ConcurrentHashMap.newKeySet();
    if (claimedCustomers != null) {
      set.addAll(claimedCustomers);
    }
    claimed.put(promotionId, set);
    seqs.put(promotionId, new AtomicLong(lastSeq));
  }

  /**
   * 在活动自己的锁里完成一次抢券，语义对齐 Jedis 的那段 Lua。
   * <p>
   * 顺序是：没有库存键则尚未开始；顾客已在集合里则重复；库存减到 0 及以下则卖完并撤销本次修改；
   * 否则分配序号，把令牌放进待处理队列。成功不写 PostgreSQL。
   *
   * @param promotionId 活动编号
   * @param customerId 顾客编号
   * @return 与 Lua 相同的四个状态码。成功时 seq 为新序号，remaining 为扣减后的剩余张数
   */
  @Override
  public GrabAttempt tryGrab(String promotionId, String customerId) {
    synchronized (lock(promotionId)) {
      AtomicLong stock = stocks.get(promotionId);
      if (stock == null) {
        // 库存键不存在：活动还没到 publishTime，或尚未 initStock。对应 Lua 返回 -3。
        return new GrabAttempt(GrabAttempt.NOT_STARTED, 0, 0);
      }
      Set<String> set = claimed.computeIfAbsent(promotionId, id -> ConcurrentHashMap.newKeySet());
      if (!set.add(customerId)) {
        // 顾客已经在已抢集合里。Set.add 返回 false，本次不扣库存。对应 Lua 返回 -1。
        return new GrabAttempt(GrabAttempt.DUPLICATE, 0, stock.get());
      }
      long remaining = stock.getAndDecrement();
      if (remaining <= 0) {
        // 减之前已经没有可卖张数。把库存和已抢集合都改回去，避免白占一个顾客名额。对应 Lua 返回 -2。
        stock.incrementAndGet();
        set.remove(customerId);
        return new GrabAttempt(GrabAttempt.SOLD_OUT, 0, 0);
      }
      long seq = seqs.computeIfAbsent(promotionId, id -> new AtomicLong()).incrementAndGet();
      long left = remaining - 1;
      pendingGrabs.offer(new GrabToken(promotionId, customerId, seq, left));
      return new GrabAttempt(GrabAttempt.SUCCESS, seq, left);
    }
  }

  /**
   * 把库存加 1，并尝试把顾客从已抢集合去掉。
   * <p>
   * 不碰抢券队列。库存键不存在或集合不存在时跳过对应的那一步。
   *
   * @param promotionId 活动编号
   * @param customerId 要撤销的顾客
   */
  @Override
  public void compensateGrab(String promotionId, String customerId) {
    synchronized (lock(promotionId)) {
      AtomicLong stock = stocks.get(promotionId);
      if (stock != null) {
        stock.incrementAndGet();
      }
      Set<String> set = claimed.get(promotionId);
      if (set != null) {
        set.remove(customerId);
      }
    }
  }

  /**
   * 取出一条处理中的令牌；没有的话再从待处理队列阻塞等待。
   * <p>
   * 处理中的队头只窥视、不移除，所以 ack 之前下一次调用还会得到同一条。这对应 Redis Stream 里
   * 尚未 XACK 的 pending 消息。新取到的令牌会放进处理中队列再返回。
   *
   * @param timeoutMillis 待处理队列为空时最多等待的毫秒数
   * @return 令牌；超时、或等待时线程被中断，则返回 null
   */
  @Override
  public GrabToken pollInflight(long timeoutMillis) {
    synchronized (grabQueueLock) {
      GrabToken inflight = inflightGrabs.peek();
      if (inflight != null) {
        // 已经有一条交给 Persist、还没 ack。继续返回它，避免把它当成新的一次扣减。
        return inflight;
      }
    }
    GrabToken next;
    try {
      next = pendingGrabs.poll(Math.max(timeoutMillis, 0), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      // 等待被中断：把中断标记设回去，让 Persist 的循环能够退出。这一轮没有令牌。
      Thread.currentThread().interrupt();
      return null;
    }
    if (next == null) {
      return null;
    }
    synchronized (grabQueueLock) {
      GrabToken inflight = inflightGrabs.peek();
      if (inflight != null) {
        // 等待期间另一条线程已经占用了处理中槽位。把刚取出的令牌放回队头，先返回那条未 ack 的。
        pendingGrabs.offerFirst(next);
        return inflight;
      }
      inflightGrabs.offer(next);
      return next;
    }
  }

  /**
   * 从处理中队列移除这条令牌，表示 PostgreSQL 已经收下（或确认是重复）。
   * <p>
   * 相等判断只看令牌正文。null 直接返回。不改库存。
   *
   * @param token {@link #pollInflight} 返回的令牌
   */
  @Override
  public void ackGrab(GrabToken token) {
    if (token == null) {
      return;
    }
    synchronized (grabQueueLock) {
      inflightGrabs.remove(token);
    }
  }

  /**
   * 把令牌从处理中队列移回待处理队列的末尾。
   * <p>
   * Persist 暂时还不能落库（例如活动行还没出现）时调用。null 直接返回。不改库存。
   *
   * @param token 需要稍后再读的令牌
   */
  @Override
  public void deferInflight(GrabToken token) {
    if (token == null) {
      return;
    }
    synchronized (grabQueueLock) {
      inflightGrabs.remove(token);
      pendingGrabs.offer(token);
    }
  }

  /**
   * 待处理条数加上处理中条数。
   * <p>
   * Command 用它确认抢券队列已经排空。加锁是为了两个数来自同一时刻。
   *
   * @return 尚未 ack 的令牌数
   */
  @Override
  public int pendingGrabCount() {
    synchronized (grabQueueLock) {
      return pendingGrabs.size() + inflightGrabs.size();
    }
  }

  /**
   * 热路径库存是否已经放进 {@link #stocks}。
   *
   * @param promotionId 活动编号
   * @return 调用过 {@link #initStock} 则为 true
   */
  @Override
  public boolean stockKeysPresent(String promotionId) {
    return stocks.containsKey(promotionId);
  }

  /**
   * 把该活动的事件序号加 1 并返回。库存未初始化时也会从 1 开始。
   * <p>
   * 供开始事件、结束事件使用。抢券成功的序号在 {@link #tryGrab} 里分配。
   *
   * @param promotionId 活动编号
   * @return 递增后的序号
   */
  @Override
  public long nextSeq(String promotionId) {
    return seqs.computeIfAbsent(promotionId, id -> new AtomicLong()).incrementAndGet();
  }

  /**
   * 返回进行中的活动读模型。返回的是副本，调用方改这个集合不会改到内部 Map。
   *
   * @return 当前 {@link #promotions} 里的活动
   */
  @Override
  public Collection<PromotionEntity> activePromotions() {
    return new ArrayList<PromotionEntity>(promotions.values());
  }

  /**
   * 扫券读模型，留下顾客编号相同的券。
   *
   * @param customerId 顾客编号
   * @return 该顾客的券。没有时为空列表
   */
  @Override
  public Collection<CouponEntity<String>> customerCoupons(String customerId) {
    List<CouponEntity<String>> result = new ArrayList<CouponEntity<String>>();
    for (CouponEntity<String> coupon : couponsByKey.values()) {
      if (customerId.equals(coupon.getCustomerId())) {
        result.add(coupon);
      }
    }
    return result;
  }

  /**
   * 取出券 id 大于 {@code latestId} 的读模型，并按 id 从小到大排序。
   * <p>
   * 对应 Jedis 对有序集合 {@code seckill:coupons_by_id} 的按分数范围读取，且不包含等于 {@code latestId} 的成员。
   *
   * @param latestId 调用方已经见过的最大券编号
   * @return 更新的券
   */
  @Override
  public Collection<CouponEntity<String>> couponsAfter(int latestId) {
    List<CouponEntity<String>> result = new ArrayList<CouponEntity<String>>();
    for (CouponEntity<String> coupon : couponsByKey.values()) {
      if (coupon.getId() > latestId) {
        result.add(coupon);
      }
    }
    Collections.sort(result, new Comparator<CouponEntity<String>>() {
      @Override
      public int compare(CouponEntity<String> left, CouponEntity<String> right) {
        return Integer.compare(left.getId(), right.getId());
      }
    });
    return result;
  }

  /**
   * 按活动编号放入或覆盖进行中的活动。投影 {@code PromotionStartEvent} 时由 Event 服务调用。
   *
   * @param promotion 活动实体，键用它的 promotionId
   */
  @Override
  public void saveActivePromotion(PromotionEntity promotion) {
    promotions.put(promotion.getPromotionId(), promotion);
  }

  /**
   * 从进行中的活动读模型移除。已保存的券不动。
   *
   * @param promotionId 活动编号
   */
  @Override
  public void removeActivePromotion(String promotionId) {
    promotions.remove(promotionId);
  }

  /**
   * 按 {@code promotionId:customerId} 保存券。这个键已存在时返回旧券，不分配新 id。
   *
   * @param coupon 待写入的券。新券会就地 {@code setId}
   * @return 读模型里最终的那一张
   */
  @Override
  public CouponEntity<String> saveCoupon(CouponEntity<String> coupon) {
    String key = coupon.getPromotionId() + ":" + coupon.getCustomerId();
    CouponEntity<String> existing = couponsByKey.get(key);
    if (existing != null) {
      // 同一活动同一顾客已经投影过。重复的 Kafka 消息不应再生成一张新券。
      return existing;
    }
    coupon.setId(couponId.incrementAndGet());
    couponsByKey.put(key, coupon);
    return coupon;
  }

  /**
   * 读投影进度。还没有 {@link #setAppliedSeq} 时返回 0。
   *
   * @param promotionId 活动编号
   * @return 已按序投影的最大序号
   */
  @Override
  public long appliedSeq(String promotionId) {
    AtomicLong value = applied.get(promotionId);
    return value == null ? 0L : value.get();
  }

  /**
   * 覆盖该活动的投影进度。Event 服务在按序应用一条带序号的消息后调用。
   *
   * @param promotionId 活动编号
   * @param seq 刚刚投影成功的序号
   */
  @Override
  public void setAppliedSeq(String promotionId, long seq) {
    applied.put(promotionId, new AtomicLong(seq));
  }

  /**
   * 把跳号的事件追加到该活动的缓冲区末尾。
   *
   * @param event 还不能按序投影的消息
   */
  @Override
  public void buffer(EventMessageDto event) {
    buffers.computeIfAbsent(event.getPromotionId(), id -> new CopyOnWriteArrayList<EventMessageDto>()).add(event);
  }

  /**
   * 复制并清空该活动的缓冲区。
   * <p>
   * 先复制再清空，返回给 Event 服务的列表不会被后续 {@link #buffer} 继续追加。
   *
   * @param promotionId 活动编号
   * @return 这一轮取出的消息。没有缓冲区时为空列表
   */
  @Override
  public List<EventMessageDto> drainBuffer(String promotionId) {
    List<EventMessageDto> list = buffers.get(promotionId);
    if (list == null) {
      return Collections.emptyList();
    }
    List<EventMessageDto> copy = new ArrayList<EventMessageDto>(list);
    list.clear();
    return copy;
  }

  /**
   * 在券读模型里按顾客过滤，再按活动编号过滤，包装成事件消息。
   * <p>
   * 查询页搜索走 Elasticsearch，不调用本方法。这里的消息类型固定为 {@code CouponGrabbedEvent}，
   * 正文固定是空 JSON 对象 {@code "{}"}，不是券实体本身。
   *
   * @param customerId 顾客编号。null 会按空串去比较
   * @param promotionId 活动编号。null 或空串表示该顾客的全部券
   * @return 过滤后的消息列表
   */
  @Override
  public List<EventMessageDto> searchCoupons(String customerId, String promotionId) {
    return customerCoupons(customerId == null ? "" : customerId).stream()
        .filter(coupon -> promotionId == null || promotionId.isEmpty() || promotionId.equals(coupon.getPromotionId()))
        .map(coupon -> new EventMessageDto("CouponGrabbedEvent", coupon.getPromotionId(), "{}"))
        .collect(Collectors.toList());
  }

  /**
   * 同一活动编号始终得到同一把锁，让这个活动的库存和已抢集合串行修改。
   * <p>
   * {@code intern} 把相同内容的字符串收成同一个对象，这样不同调用点也能对上同一把锁。
   *
   * @param promotionId 活动编号
   * @return 用作 {@code synchronized} 的对象
   */
  private Object lock(String promotionId) {
    return ("lock-" + promotionId).intern();
  }
}
