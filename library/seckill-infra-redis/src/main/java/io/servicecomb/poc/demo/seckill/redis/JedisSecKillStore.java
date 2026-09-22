package io.servicecomb.poc.demo.seckill.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.servicecomb.poc.demo.seckill.dto.EventMessageDto;
import io.servicecomb.poc.demo.seckill.entities.CouponEntity;
import io.servicecomb.poc.demo.seckill.entities.PromotionEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class JedisSecKillStore implements SecKillStore {

  static final String GRABS_KEY = "seckill:grabs";
  static final String INFLIGHT_KEY = "seckill:grabs:inflight";

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
          + "redis.call('RPUSH', KEYS[4], payload) "
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
  public GrabToken pollInflight() {
    Jedis jedis = pool.getResource();
    try {
      String existing = jedis.lindex(INFLIGHT_KEY, 0);
      if (existing != null) {
        return GrabToken.parse(existing);
      }
      String moved = jedis.rpoplpush(GRABS_KEY, INFLIGHT_KEY);
      return GrabToken.parse(moved);
    } finally {
      jedis.close();
    }
  }

  @Override
  public void ackGrab(GrabToken token) {
    if (token == null) {
      return;
    }
    Jedis jedis = pool.getResource();
    try {
      jedis.lrem(INFLIGHT_KEY, 1, token.payload());
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
      jedis.lrem(INFLIGHT_KEY, 1, token.payload());
      jedis.rpush(GRABS_KEY, token.payload());
    } finally {
      jedis.close();
    }
  }

  @Override
  public int pendingGrabCount() {
    Jedis jedis = pool.getResource();
    try {
      return (int) (jedis.llen(GRABS_KEY) + jedis.llen(INFLIGHT_KEY));
    } finally {
      jedis.close();
    }
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
