package io.servicecomb.poc.demo.seckill;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SecKillRecoveryCheckResult<T> {
  private final boolean startEventAvailable;
  private final boolean finishEventAvailable;
  private final int remainingCoupons;
  private final Set<T> claimedCustomers;
  private final long lastSeq;

  public boolean isStarted() {
    return startEventAvailable;
  }

  public int remainingCoupons() {
    return remainingCoupons;
  }

  public boolean isFinished() {
    return finishEventAvailable;
  }

  public Set<T> getClaimedCustomers() {
    return claimedCustomers;
  }

  public long lastSeq() {
    return lastSeq;
  }

  public SecKillRecoveryCheckResult(int remainingCoupons) {
    startEventAvailable = false;
    finishEventAvailable = false;
    this.remainingCoupons = remainingCoupons;
    this.claimedCustomers = ConcurrentHashMap.newKeySet();
    this.lastSeq = 0;
  }

  public SecKillRecoveryCheckResult(boolean startEventAvailable, boolean finishEventAvailable, int remainingCoupons,
      Set<T> claimedCustomers) {
    this(startEventAvailable, finishEventAvailable, remainingCoupons, claimedCustomers, 0);
  }

  public SecKillRecoveryCheckResult(boolean startEventAvailable, boolean finishEventAvailable, int remainingCoupons,
      Set<T> claimedCustomers, long lastSeq) {
    this.startEventAvailable = startEventAvailable;
    this.finishEventAvailable = finishEventAvailable;
    this.remainingCoupons = remainingCoupons;
    this.claimedCustomers = claimedCustomers;
    this.lastSeq = lastSeq;
  }
}
