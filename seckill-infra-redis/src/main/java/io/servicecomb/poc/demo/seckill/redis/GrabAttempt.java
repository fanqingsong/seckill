package io.servicecomb.poc.demo.seckill.redis;

public class GrabAttempt {

  public static final int SUCCESS = 1;
  public static final int DUPLICATE = -1;
  public static final int SOLD_OUT = -2;
  public static final int NOT_STARTED = -3;

  private final int code;
  private final long seq;
  private final long remaining;

  public GrabAttempt(int code, long seq, long remaining) {
    this.code = code;
    this.seq = seq;
    this.remaining = remaining;
  }

  public boolean isSuccess() {
    return code == SUCCESS;
  }

  public boolean isDuplicate() {
    return code == DUPLICATE;
  }

  public boolean isSoldOut() {
    return code == SOLD_OUT || code == NOT_STARTED;
  }

  public long getSeq() {
    return seq;
  }

  public long getRemaining() {
    return remaining;
  }
}
