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

public class JedisSecKillStore implements SecKillStore {

  static final String GRABS_KEY = "seckill:grabs";
  static final String GRAB_GROUP = "persist";
  private static final long RECLAIM_IDLE_MS = 30_000L;
  private static final StreamEntryID CONSUMER_PENDING = new StreamEntryID() {
    @Override
    public String toString() {
      return "0";
    }
  };

  private final String consumer = "persist-" + UUID.randomUUID();
  private volatile boolean groupReady;

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

  private static final String COMPENSATE_LUA =
      "redis.call('INCR', KEYS[1]) redis.call('SREM', KEYS[2], ARGV[1]) return 1";

  private final JedisPool pool;
  private final ObjectMapper mapper = new ObjectMapper();

  public JedisSecKillStore(JedisPool pool) {
    this.pool = pool;
  }

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

  @Override
  public void compensateGrab(String promotionId, String customerId) {
    Jedis jedis = pool.getResource();
    try {
      jedis.eval(COMPENSATE_LUA, 2, stockKey(promotionId), claimedKey(promotionId), customerId);
    } finally {
      jedis.close();
    }
  }

  @Override
  public GrabToken pollInflight(long timeoutMillis) {
    Jedis jedis = pool.getResource();
    try {
      ensureGroup(jedis);
      GrabToken owned = readOne(jedis, CONSUMER_PENDING, -1);
      if (owned != null) {
        return owned;
      }
      GrabToken reclaimed = reclaimOne(jedis);
      if (reclaimed != null) {
        return reclaimed;
      }
      int blockMillis = timeoutMillis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(timeoutMillis, 0);
      return readOne(jedis, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY, blockMillis);
    } finally {
      jedis.close();
    }
  }

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
      return 0;
    } finally {
      jedis.close();
    }
  }

  private void ensureGroup(Jedis jedis) {
    if (groupReady) {
      return;
    }
    synchronized (this) {
      if (groupReady) {
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

  private GrabToken reclaimOne(Jedis jedis) {
    Map.Entry<StreamEntryID, List<StreamEntry>> claimed = jedis.xautoclaim(GRABS_KEY, GRAB_GROUP, consumer,
        RECLAIM_IDLE_MS, StreamEntryID.MINIMUM_ID, XAutoClaimParams.xAutoClaimParams().count(1));
    if (claimed == null || claimed.getValue() == null || claimed.getValue().isEmpty()) {
      return null;
    }
    return toToken(claimed.getValue().get(0));
  }

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

  @Override
  public boolean stockKeysPresent(String promotionId) {
    Jedis jedis = pool.getResource();
    try {
      return Boolean.TRUE.equals(jedis.exists(stockKey(promotionId)));
    } finally {
      jedis.close();
    }
  }

  @Override
  public long nextSeq(String promotionId) {
    Jedis jedis = pool.getResource();
    try {
      return jedis.incr(seqKey(promotionId));
    } finally {
      jedis.close();
    }
  }

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

  @Override
  public void saveActivePromotion(PromotionEntity promotion) {
    Jedis jedis = pool.getResource();
    try {
      jedis.hset("seckill:active_promotions", promotion.getPromotionId(), write(promotion));
    } finally {
      jedis.close();
    }
  }

  @Override
  public void removeActivePromotion(String promotionId) {
    Jedis jedis = pool.getResource();
    try {
      jedis.hdel("seckill:active_promotions", promotionId);
    } finally {
      jedis.close();
    }
  }

  @Override
  public CouponEntity<String> saveCoupon(CouponEntity<String> coupon) {
    Jedis jedis = pool.getResource();
    try {
      String key = coupon.getPromotionId() + ":" + coupon.getCustomerId();
      String existing = jedis.get("seckill:coupon:" + key);
      if (existing != null) {
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

  @Override
  public void setAppliedSeq(String promotionId, long seq) {
    Jedis jedis = pool.getResource();
    try {
      jedis.set("seckill:applied_seq:" + promotionId, String.valueOf(seq));
    } finally {
      jedis.close();
    }
  }

  @Override
  public void buffer(EventMessageDto event) {
    Jedis jedis = pool.getResource();
    try {
      jedis.rpush("seckill:buffer:" + event.getPromotionId(), write(event));
    } finally {
      jedis.close();
    }
  }

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

  private String stockKey(String promotionId) {
    return "seckill:stock:" + promotionId;
  }

  private String claimedKey(String promotionId) {
    return "seckill:claimed:" + promotionId;
  }

  private String seqKey(String promotionId) {
    return "seckill:seq:" + promotionId;
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private CouponEntity<String> coupon(String json) {
    return read(json, CouponEntity.class);
  }
}
