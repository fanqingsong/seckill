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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemorySecKillStore implements SecKillStore {

  private final Map<String, AtomicLong> stocks = new ConcurrentHashMap<String, AtomicLong>();
  private final Map<String, Set<String>> claimed = new ConcurrentHashMap<String, Set<String>>();
  private final Map<String, AtomicLong> seqs = new ConcurrentHashMap<String, AtomicLong>();
  private final Map<String, PromotionEntity> promotions = new ConcurrentHashMap<String, PromotionEntity>();
  private final Map<String, CouponEntity<String>> couponsByKey = new ConcurrentHashMap<String, CouponEntity<String>>();
  private final Map<String, AtomicLong> applied = new ConcurrentHashMap<String, AtomicLong>();
  private final Map<String, List<EventMessageDto>> buffers = new ConcurrentHashMap<String, List<EventMessageDto>>();
  private final AtomicInteger couponId = new AtomicInteger();

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

  @Override
  public GrabAttempt tryGrab(String promotionId, String customerId) {
    synchronized (lock(promotionId)) {
      AtomicLong stock = stocks.get(promotionId);
      if (stock == null) {
        return new GrabAttempt(GrabAttempt.NOT_STARTED, 0, 0);
      }
      Set<String> set = claimed.computeIfAbsent(promotionId, id -> ConcurrentHashMap.newKeySet());
      if (!set.add(customerId)) {
        return new GrabAttempt(GrabAttempt.DUPLICATE, 0, stock.get());
      }
      long remaining = stock.getAndDecrement();
      if (remaining <= 0) {
        stock.incrementAndGet();
        set.remove(customerId);
        return new GrabAttempt(GrabAttempt.SOLD_OUT, 0, 0);
      }
      long seq = seqs.computeIfAbsent(promotionId, id -> new AtomicLong()).incrementAndGet();
      return new GrabAttempt(GrabAttempt.SUCCESS, seq, remaining - 1);
    }
  }

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

  @Override
  public long nextSeq(String promotionId) {
    return seqs.computeIfAbsent(promotionId, id -> new AtomicLong()).incrementAndGet();
  }

  @Override
  public Collection<PromotionEntity> activePromotions() {
    return new ArrayList<PromotionEntity>(promotions.values());
  }

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

  @Override
  public void saveActivePromotion(PromotionEntity promotion) {
    promotions.put(promotion.getPromotionId(), promotion);
  }

  @Override
  public void removeActivePromotion(String promotionId) {
    promotions.remove(promotionId);
  }

  @Override
  public CouponEntity<String> saveCoupon(CouponEntity<String> coupon) {
    String key = coupon.getPromotionId() + ":" + coupon.getCustomerId();
    CouponEntity<String> existing = couponsByKey.get(key);
    if (existing != null) {
      return existing;
    }
    coupon.setId(couponId.incrementAndGet());
    couponsByKey.put(key, coupon);
    return coupon;
  }

  @Override
  public long appliedSeq(String promotionId) {
    AtomicLong value = applied.get(promotionId);
    return value == null ? 0L : value.get();
  }

  @Override
  public void setAppliedSeq(String promotionId, long seq) {
    applied.put(promotionId, new AtomicLong(seq));
  }

  @Override
  public void buffer(EventMessageDto event) {
    buffers.computeIfAbsent(event.getPromotionId(), id -> new CopyOnWriteArrayList<EventMessageDto>()).add(event);
  }

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

  @Override
  public List<EventMessageDto> searchCoupons(String customerId, String promotionId) {
    return customerCoupons(customerId == null ? "" : customerId).stream()
        .filter(coupon -> promotionId == null || promotionId.isEmpty() || promotionId.equals(coupon.getPromotionId()))
        .map(coupon -> new EventMessageDto("CouponGrabbedEvent", coupon.getPromotionId(), "{}"))
        .collect(Collectors.toList());
  }

  private Object lock(String promotionId) {
    return ("lock-" + promotionId).intern();
  }
}
