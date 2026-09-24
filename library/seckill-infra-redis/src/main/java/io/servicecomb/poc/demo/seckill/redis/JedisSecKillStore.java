/*
 * ┌─ 文件 ────────────────────────────────────────┐
 * │ JedisSecKillStore.java                        │
 * │ 链路：抢券 · Redis 热路径                     │
 * └───────────────────────────────────────────────┘
 *
 *   Command.tryGrab
 *      │
 *      ▼
 *   【本文件】一段 Lua，在 Redis 里一次做完
 *      │
 *      ├── seckill:stock:{id}     剩余张数
 *      ├── seckill:claimed:{id}   已抢顾客
 *      └── seckill:grabs          Stream，交给 Persist
 *
 *   查询用的活动列表、券，写在另一组键上，不走这段 Lua。
 *
 * 一句话：HTTP 线程只扣 Redis，不写 PostgreSQL，也不发 Kafka。
 */

package io.servicecomb.poc.demo.seckill.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamGroupInfo;

/**
 * {@link SecKillStore} 的 Redis 实现。{@code seckill.infra.mode=prod} 时由 {@link SecKillRedisConfig} 创建。
 * <p>
 * 数据分成三块，键名都写在本类里：
 * 热路径库存是字符串 {@code seckill:stock:{promotionId}}、集合 {@code seckill:claimed:{promotionId}}、
 * 字符串 {@code seckill:seq:{promotionId}}。抢券队列是 Stream {@code seckill:grabs}，消费者组 {@code persist}。
 * 查询读模型是另一组键（进行中的活动、券、投影序号、乱序缓冲），和库存键分开。
 * 抢券只执行下面的一段 Lua：判断、扣减、写入 Stream 在 Redis 里一次完成。HTTP 线程不写 PostgreSQL，也不发 Kafka。
 */
public class JedisSecKillStore implements SecKillStore {

  /** 抢券队列。Redis Stream，字段名是 {@code payload}，正文由 Lua 用制表符拼出。 */
  static final String GRABS_KEY = "seckill:grabs";
  /** Stream 消费者组名。Persist 只从这个组读，组内未 ack 的条目可以再次投递。 */
  static final String GRAB_GROUP = "persist";
  /** 条目在组里闲置超过这么多毫秒，就允许当前消费者把别人没 ack 的消息领走。 */
  private static final long RECLAIM_IDLE_MS = 30_000L;
  /**
   * 读「本消费者自己还没 ack 的条目」时使用的位置。
   * Stream 规定 id {@code 0} 表示该消费者的 pending，而不是新消息。
   */
  private static final StreamEntryID CONSUMER_PENDING = new StreamEntryID() {
    @Override
    public String toString() {
      return "0";
    }
  };

  /** 本进程在消费者组里的名字。重启后是新名字，超过闲置时间的旧 pending 靠 XAUTOCLAIM 领回。 */
  private final String consumer = "persist-" + UUID.randomUUID();
  /** 本进程是否已经确认消费者组存在。避免每次取令牌都向 Redis 发 XGROUP CREATE。 */
  private volatile boolean groupReady;

  /**
   * 热路径抢券脚本。KEYS 依次是库存、已抢集合、序号、抢券 Stream；ARGV 是顾客编号和活动编号。
   * <p>
   * 脚本自己决定四件事，调用方只看返回的三个数：
   * 库存键不存在返回 {@code {-3,0,0}}（尚未开始）；顾客已在集合里返回 {@code {-1,0,0}}（重复，不扣库存）；
   * 剩余张数不大于 0 返回 {@code {-2,0,0}}（卖完）；否则 SADD、DECR、INCR 序号，再 XADD 一条 payload，
   * 返回 {@code {1, seq, remaining}}。remaining 是扣减后的张数。
   */
  private static final String GRAB_LUA =
      "if redis.call('EXISTS', KEYS[1]) == 0 then return {-3, 0, 0} end "
          + "if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return {-1, 0, 0} end "
          + "local stock = tonumber(redis.call('GET', KEYS[1]) or '0') "
          + "if stock <= 0 then return {-2, 0, 0} end "
          + "redis.call('SADD', KEYS[2], ARGV[1]) "
          + "redis.call('DECR', KEYS[1]) "
          + "local seq = redis.call('INCR', KEYS[3]) "
          + "local remaining = stock - 1 "
          + "local payload = ARGV[2] .. '\\t' .. ARGV[1] .. '\\t' .. tostring(seq) .. '\\t' .. tostring(remaining) "
          + "redis.call('XADD', KEYS[4], '*', 'payload', payload) "
          + "return {1, seq, remaining}";

  /**
   * 补偿脚本：库存字符串加 1，并从已抢集合去掉该顾客。
   * 不删除 Stream 里可能已经写下的令牌。KEYS 是库存和已抢集合，ARGV 是顾客编号。
   */
  private static final String COMPENSATE_LUA =
      "redis.call('INCR', KEYS[1]) redis.call('SREM', KEYS[2], ARGV[1]) return 1";

  private final JedisPool pool;
  /** 读模型以 JSON 字符串放进 Redis。库存和抢券队列不用它。 */
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * @param pool 连接池。每次命令借一条连接，用完在 finally 里还回去
   */
  public JedisSecKillStore(JedisPool pool) {
    this.pool = pool;
  }

  /**
   * 初始化热路径三把键：SET 库存、删掉旧的已抢集合再 SADD、SET 序号。
   * <p>
   * 不创建 Stream，也不写查询读模型。已抢顾客为 null 时集合保持被删除后的空状态。
   *
   * @param promotionId 活动编号，拼进 {@code seckill:stock:}、{@code seckill:claimed:}、{@code seckill:seq:}
   * @param remaining 还可卖的张数
   * @param claimedCustomers 恢复时已经抢到的顾客
   * @param lastSeq 已经分配过的最大序号
   */
  @Override
  public void initStock(String promotionId, int remaining, Set<String> claimedCustomers, long lastSeq) {
    Jedis jedis = pool.getResource();
    try {
      jedis.set(stockKey(promotionId), String.valueOf(remaining));
      jedis.del(claimedKey(promotionId));
      if (claimedCustomers != null) {
        for (String customer : claimedCustomers) {
          jedis.sadd(claimedKey(promotionId), customer);
        }
      }
      jedis.set(seqKey(promotionId), String.valueOf(lastSeq));
    } finally {
      jedis.close();
    }
  }

  /**
   * 执行抢券 Lua。四个 KEYS 和两个 ARGV 的顺序必须和脚本注释一致。
   *
   * @param promotionId 活动编号，同时作为 payload 的第一段
   * @param customerId 顾客编号，脚本用它做 SISMEMBER / SADD
   * @return Lua 返回的状态码、序号和剩余张数
   */
  @Override
  public GrabAttempt tryGrab(String promotionId, String customerId) {
    Jedis jedis = pool.getResource();
    try {
      @SuppressWarnings("unchecked")
      List<Long> result = (List<Long>) jedis.eval(GRAB_LUA, 4, stockKey(promotionId), claimedKey(promotionId),
          seqKey(promotionId), GRABS_KEY, customerId, promotionId);
      return new GrabAttempt(result.get(0).intValue(), result.get(1), result.get(2));
    } finally {
      jedis.close();
    }
  }

  /**
   * 执行补偿 Lua：INCR 库存，SREM 已抢集合。
   *
   * @param promotionId 活动编号
   * @param customerId 要移出集合的顾客
   */
  @Override
  public void compensateGrab(String promotionId, String customerId) {
    Jedis jedis = pool.getResource();
    try {
      jedis.eval(COMPENSATE_LUA, 2, stockKey(promotionId), claimedKey(promotionId), customerId);
    } finally {
      jedis.close();
    }
  }

  /**
   * 按「自己的 pending → 闲置超过 30 秒的别人的 pending → 新消息」取出一条令牌。
   * <p>
   * 新消息使用 Stream 的特殊 id {@code >}（{@code XREADGROUP_UNDELIVERED_ENTRY}），表示还没投递过的条目。
   * 取到的令牌带上条目 id，Persist 落库成功后用它 XACK。
   *
   * @param timeoutMillis 只作用于第三步等待新消息的时间。pending 和认领不等待
   * @return 令牌；三步都没有消息时返回 null
   */
  @Override
  public GrabToken pollInflight(long timeoutMillis) {
    Jedis jedis = pool.getResource();
    try {
      ensureGroup(jedis);
      GrabToken owned = readOne(jedis, CONSUMER_PENDING, -1);
      if (owned != null) {
        // 本消费者还有没 ack 的条目。block 传 -1，表示这一步不阻塞。
        return owned;
      }
      GrabToken reclaimed = reclaimOne(jedis);
      if (reclaimed != null) {
        // 别的消费者领走后闲置超过 RECLAIM_IDLE_MS。领回到本消费者再交给 Persist。
        return reclaimed;
      }
      int blockMillis = timeoutMillis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(timeoutMillis, 0);
      return readOne(jedis, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY, blockMillis);
    } finally {
      jedis.close();
    }
  }

  /**
   * 对条目执行 XACK，再 XDEL，使它不再占用 Stream。
   * <p>
   * 令牌或条目 id 为 null 时直接返回（内存实现造出来的令牌没有 id）。不改库存。
   *
   * @param token 带 messageId 的令牌
   */
  @Override
  public void ackGrab(GrabToken token) {
    if (token == null || token.getMessageId() == null) {
      return;
    }
    Jedis jedis = pool.getResource();
    try {
      StreamEntryID id = new StreamEntryID(token.getMessageId());
      jedis.xack(GRABS_KEY, GRAB_GROUP, id);
      jedis.xdel(GRABS_KEY, id);
    } finally {
      jedis.close();
    }
  }

  /**
   * 把同一段 payload 重新 XADD 到 Stream 末尾，并 ack、删除旧条目。
   * <p>
   * Persist 在活动行还没出现时调用，让这条令牌排到后面再被读到。没有 messageId 时只追加，不删旧条目。
   *
   * @param token 需要推迟的令牌。null 直接返回
   */
  @Override
  public void deferInflight(GrabToken token) {
    if (token == null) {
      return;
    }
    Jedis jedis = pool.getResource();
    try {
      ensureGroup(jedis);
      jedis.xadd(GRABS_KEY, StreamEntryID.NEW_ENTRY, Collections.singletonMap("payload", token.payload()));
      if (token.getMessageId() != null) {
        StreamEntryID id = new StreamEntryID(token.getMessageId());
        jedis.xack(GRABS_KEY, GRAB_GROUP, id);
        jedis.xdel(GRABS_KEY, id);
      }
    } finally {
      jedis.close();
    }
  }

  /**
   * 用 XINFO GROUPS 读出消费者组 {@code persist} 的 pending 条数，加上尚未投递的 lag。
   * <p>
   * Stream 或组还不存在时 Redis 抛 {@link JedisDataException}，这时视为 0，结束判断可以继续。
   *
   * @return 还没 ack 的令牌数
   */
  @Override
  public int pendingGrabCount() {
    Jedis jedis = pool.getResource();
    try {
      ensureGroup(jedis);
      List<StreamGroupInfo> groups = jedis.xinfoGroups(GRABS_KEY);
      for (StreamGroupInfo group : groups) {
        if (!GRAB_GROUP.equals(group.getName())) {
          continue;
        }
        Object lag = group.getGroupInfo().get("lag");
        long undelivered = lag instanceof Number ? ((Number) lag).longValue() : 0L;
        return (int) (group.getPending() + undelivered);
      }
      return 0;
    } catch (JedisDataException e) {
      // 抢券 Stream 还没被 XADD 或 XGROUP 创建。队列视为空。
      return 0;
    } finally {
      jedis.close();
    }
  }

  /**
   * 保证 Stream {@code seckill:grabs} 上存在消费者组 {@code persist}，并从 id {@code 0-0} 开始可读。
   * <p>
   * 最后一个参数 {@code true} 表示流不存在时一并创建（MKSTREAM）。组已存在时 Redis 返回 BUSYGROUP，
   * 这是预期情况，吞掉；其它错误继续抛出。
   *
   * @param jedis 当前借出的连接
   */
  private void ensureGroup(Jedis jedis) {
    if (groupReady) {
      return;
    }
    synchronized (this) {
      if (groupReady) {
        // 另一个线程刚刚创建完组。
        return;
      }
      try {
        jedis.xgroupCreate(GRABS_KEY, GRAB_GROUP, new StreamEntryID(0, 0), true);
      } catch (JedisDataException e) {
        if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
          throw e;
        }
      }
      groupReady = true;
    }
  }

  /**
   * 用 XREADGROUP 从指定位置读最多 1 条，并解析成令牌。
   *
   * @param jedis 当前连接
   * @param position {@code 0} 读本消费者 pending；{@code >} 读尚未投递的新消息
   * @param blockMillis 大于等于 0 时最多阻塞这么久；负数表示不设置阻塞
   * @return 第一条能解析的令牌；没有批次时返回 null
   */
  private GrabToken readOne(Jedis jedis, StreamEntryID position, int blockMillis) {
    XReadGroupParams params = XReadGroupParams.xReadGroupParams().count(1);
    if (blockMillis >= 0) {
      params.block(blockMillis);
    }
    Map<String, StreamEntryID> streams = new HashMap<String, StreamEntryID>();
    streams.put(GRABS_KEY, position);
    List<Map.Entry<String, List<StreamEntry>>> batches = jedis.xreadGroup(GRAB_GROUP, consumer, params, streams);
    return firstToken(batches);
  }

  /**
   * 把闲置至少 {@link #RECLAIM_IDLE_MS} 的一条 pending 领给本消费者。
   * <p>
   * 起始 id 用最小值，count 为 1。没有可领的条目时返回 null。
   *
   * @param jedis 当前连接
   * @return 领到的令牌
   */
  private GrabToken reclaimOne(Jedis jedis) {
    Map.Entry<StreamEntryID, List<StreamEntry>> claimed = jedis.xautoclaim(GRABS_KEY, GRAB_GROUP, consumer,
        RECLAIM_IDLE_MS, StreamEntryID.MINIMUM_ID, XAutoClaimParams.xAutoClaimParams().count(1));
    if (claimed == null || claimed.getValue() == null || claimed.getValue().isEmpty()) {
      return null;
    }
    return toToken(claimed.getValue().get(0));
  }

  /**
   * 从 XREADGROUP 的返回里取出第一条带 payload 的令牌。
   *
   * @param batches 按 Stream 分好的条目。Redis 没有新消息时可能是 null
   * @return 第一条令牌；批次为空时返回 null
   */
  private GrabToken firstToken(List<Map.Entry<String, List<StreamEntry>>> batches) {
    if (batches == null) {
      return null;
    }
    for (Map.Entry<String, List<StreamEntry>> batch : batches) {
      if (batch.getValue() == null) {
        continue;
      }
      for (StreamEntry entry : batch.getValue()) {
        GrabToken token = toToken(entry);
        if (token != null) {
          return token;
        }
      }
    }
    return null;
  }

  /**
   * 把 Stream 条目的 {@code payload} 字段解析成令牌，并附上条目 id。
   *
   * @param entry Stream 的一条记录
   * @return 令牌。没有字段或正文为空时返回 null；有正文但没有 id 时返回不带 messageId 的令牌
   */
  private GrabToken toToken(StreamEntry entry) {
    if (entry == null || entry.getFields() == null) {
      return null;
    }
    GrabToken token = GrabToken.parse(entry.getFields().get("payload"));
    if (token == null || entry.getID() == null) {
      return token;
    }
    return token.withMessageId(entry.getID().toString());
  }

  /**
   * 热路径库存键 {@code seckill:stock:{promotionId}} 是否存在。
   *
   * @param promotionId 活动编号
   * @return EXISTS 为真时 true
   */
  @Override
  public boolean stockKeysPresent(String promotionId) {
    Jedis jedis = pool.getResource();
    try {
      return Boolean.TRUE.equals(jedis.exists(stockKey(promotionId)));
    } finally {
      jedis.close();
    }
  }

  /**
   * 对序号键 {@code seckill:seq:{promotionId}} 做 INCR。键不存在时 Redis 从 0 开始加，得到 1。
   *
   * @param promotionId 活动编号
   * @return 递增后的序号
   */
  @Override
  public long nextSeq(String promotionId) {
    Jedis jedis = pool.getResource();
    try {
      return jedis.incr(seqKey(promotionId));
    } finally {
      jedis.close();
    }
  }

  /**
   * 读哈希 {@code seckill:active_promotions} 的全部 field。field 是活动编号，value 是活动 JSON。
   *
   * @return 进行中的活动。哈希为空时返回空列表
   */
  @Override
  public Collection<PromotionEntity> activePromotions() {
    Jedis jedis = pool.getResource();
    try {
      java.util.Map<String, String> all = jedis.hgetAll("seckill:active_promotions");
      List<PromotionEntity> result = new ArrayList<PromotionEntity>();
      for (String json : all.values()) {
        result.add(read(json, PromotionEntity.class));
      }
      return result;
    } finally {
      jedis.close();
    }
  }

  /**
   * 先读集合 {@code seckill:customer_coupons:{customerId}}，再按每个成员 GET {@code seckill:coupon:} 加成员。
   * <p>
   * 集合成员的形状是 {@code promotionId:customerId}。某个券键已经不在时跳过。
   *
   * @param customerId 顾客编号，拼进集合键
   * @return 该顾客的券
   */
  @Override
  public Collection<CouponEntity<String>> customerCoupons(String customerId) {
    Jedis jedis = pool.getResource();
    try {
      Set<String> keys = jedis.smembers("seckill:customer_coupons:" + customerId);
      List<CouponEntity<String>> result = new ArrayList<CouponEntity<String>>();
      for (String key : keys) {
        String json = jedis.get("seckill:coupon:" + key);
        if (json != null) {
          result.add(coupon(json));
        }
      }
      return result;
    } finally {
      jedis.close();
    }
  }

  /**
   * 从有序集合 {@code seckill:coupons_by_id} 取出分数大于 {@code latestId} 的成员。
   * <p>
   * 分数是券 id，成员是券 JSON。区间写成 {@code (latestId}，括号表示不含等于 {@code latestId} 的那张。
   *
   * @param latestId 调用方已经见过的最大券编号
   * @return 分数更大的券，顺序与 Redis 返回的一致
   */
  @Override
  public Collection<CouponEntity<String>> couponsAfter(int latestId) {
    Jedis jedis = pool.getResource();
    try {
      List<String> jsons = jedis.zrangeByScore("seckill:coupons_by_id", "(" + latestId, "+inf");
      List<CouponEntity<String>> result = new ArrayList<CouponEntity<String>>();
      for (String json : jsons) {
        result.add(coupon(json));
      }
      return result;
    } finally {
      jedis.close();
    }
  }

  /**
   * HSET {@code seckill:active_promotions}，field 为活动编号，value 为活动 JSON。
   *
   * @param promotion 要放进查询列表的活动
   */
  @Override
  public void saveActivePromotion(PromotionEntity promotion) {
    Jedis jedis = pool.getResource();
    try {
      jedis.hset("seckill:active_promotions", promotion.getPromotionId(), write(promotion));
    } finally {
      jedis.close();
    }
  }

  /**
   * HDEL {@code seckill:active_promotions} 里的这个活动。券键保留。
   *
   * @param promotionId 活动编号，即哈希的 field
   */
  @Override
  public void removeActivePromotion(String promotionId) {
    Jedis jedis = pool.getResource();
    try {
      jedis.hdel("seckill:active_promotions", promotionId);
    } finally {
      jedis.close();
    }
  }

  /**
   * 写入一张券的读模型。键 {@code seckill:coupon:{promotionId}:{customerId}} 已有 JSON 时直接返回旧券。
   * <p>
   * 新券先 INCR {@code seckill:coupon_id} 得到 id，再 SET 券 JSON、SADD 到顾客的集合、
   * ZADD 到 {@code seckill:coupons_by_id}（分数是 id，成员是 JSON）。
   *
   * @param coupon 待保存的券。新券会就地 {@code setId}
   * @return 读模型里的券。重复时是 Redis 里已有的那份
   */
  @Override
  public CouponEntity<String> saveCoupon(CouponEntity<String> coupon) {
    Jedis jedis = pool.getResource();
    try {
      String key = coupon.getPromotionId() + ":" + coupon.getCustomerId();
      String existing = jedis.get("seckill:coupon:" + key);
      if (existing != null) {
        // 同一活动同一顾客已经投影过，不再 INCR 券编号。
        return coupon(existing);
      }
      long id = jedis.incr("seckill:coupon_id");
      coupon.setId((int) id);
      String json = write(coupon);
      jedis.set("seckill:coupon:" + key, json);
      jedis.sadd("seckill:customer_coupons:" + coupon.getCustomerId(), key);
      jedis.zadd("seckill:coupons_by_id", id, json);
      return coupon;
    } finally {
      jedis.close();
    }
  }

  /**
   * GET 字符串 {@code seckill:applied_seq:{promotionId}}。键不存在时返回 0。
   *
   * @param promotionId 活动编号
   * @return 已投影的最大序号
   */
  @Override
  public long appliedSeq(String promotionId) {
    Jedis jedis = pool.getResource();
    try {
      String value = jedis.get("seckill:applied_seq:" + promotionId);
      return value == null ? 0L : Long.parseLong(value);
    } finally {
      jedis.close();
    }
  }

  /**
   * SET {@code seckill:applied_seq:{promotionId}} 为给定序号。
   *
   * @param promotionId 活动编号
   * @param seq 刚刚按序投影成功的序号
   */
  @Override
  public void setAppliedSeq(String promotionId, long seq) {
    Jedis jedis = pool.getResource();
    try {
      jedis.set("seckill:applied_seq:" + promotionId, String.valueOf(seq));
    } finally {
      jedis.close();
    }
  }

  /**
   * 把事件 JSON 从右侧推进列表 {@code seckill:buffer:{promotionId}}，保持到达顺序。
   *
   * @param event 序号有缺口、暂时不能投影的消息
   */
  @Override
  public void buffer(EventMessageDto event) {
    Jedis jedis = pool.getResource();
    try {
      jedis.rpush("seckill:buffer:" + event.getPromotionId(), write(event));
    } finally {
      jedis.close();
    }
  }

  /**
   * LRANGE {@code seckill:buffer:{promotionId}} 的全部元素，然后 DEL 这个列表键。
   *
   * @param promotionId 活动编号
   * @return 取出的消息。列表不存在时为空列表
   */
  @Override
  public List<EventMessageDto> drainBuffer(String promotionId) {
    Jedis jedis = pool.getResource();
    try {
      List<String> raw = jedis.lrange("seckill:buffer:" + promotionId, 0, -1);
      jedis.del("seckill:buffer:" + promotionId);
      List<EventMessageDto> result = new ArrayList<EventMessageDto>();
      for (String json : raw) {
        result.add(read(json, EventMessageDto.class));
      }
      return result;
    } finally {
      jedis.close();
    }
  }

  /**
   * 用顾客券集合做过滤，再按活动编号留下匹配项，包装成 {@code CouponGrabbedEvent}。
   * <p>
   * 查询页的搜索走 Elasticsearch，不调用本方法。顾客编号为 null 或空串时直接返回空列表，不再访问 Redis。
   * 消息正文是券的 JSON，不是空对象。
   *
   * @param customerId 顾客编号
   * @param promotionId 活动编号。null 或空串表示不按活动过滤
   * @return 过滤后的消息
   */
  @Override
  public List<EventMessageDto> searchCoupons(String customerId, String promotionId) {
    Collection<CouponEntity<String>> coupons =
        customerId == null || customerId.isEmpty() ? Collections.<CouponEntity<String>>emptyList()
            : customerCoupons(customerId);
    List<EventMessageDto> result = new ArrayList<EventMessageDto>();
    for (CouponEntity<String> coupon : coupons) {
      if (promotionId == null || promotionId.isEmpty() || promotionId.equals(coupon.getPromotionId())) {
        result.add(new EventMessageDto("CouponGrabbedEvent", coupon.getPromotionId(), write(coupon)));
      }
    }
    return result;
  }

  /** 热路径库存字符串键。 */
  private String stockKey(String promotionId) {
    return "seckill:stock:" + promotionId;
  }

  /** 热路径已抢顾客集合键。 */
  private String claimedKey(String promotionId) {
    return "seckill:claimed:" + promotionId;
  }

  /** 热路径事件序号字符串键。 */
  private String seqKey(String promotionId) {
    return "seckill:seq:" + promotionId;
  }

  /**
   * 把读模型对象写成 JSON。失败时包成 {@link IllegalStateException}，不写入 Redis。
   *
   * @param value 活动、券或事件消息
   * @return JSON 文本
   */
  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 把 Redis 里的 JSON 还原成对象。解析失败时包成 {@link IllegalStateException}。
   *
   * @param json Redis 中的文本
   * @param type 目标类型
   * @param <T> 由 {@code type} 决定的返回类型
   * @return 还原后的对象
   */
  private <T> T read(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 把券 JSON 读成 {@link CouponEntity}。泛型在运行时会被擦掉，所以按原始类型解析再交回调用方。
   *
   * @param json {@code seckill:coupon:} 或有序集合成员里的文本
   * @return 券实体
   */
  @SuppressWarnings("unchecked")
  private CouponEntity<String> coupon(String json) {
    return read(json, CouponEntity.class);
  }
}
