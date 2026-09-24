package io.servicecomb.poc.demo.seckill.redis;

public final class GrabToken {

  private static final String SEP = "\t";

  private final String promotionId;
  private final String customerId;
  private final long seq;
  private final long remaining;
  private final String payload;
  private final String messageId;

  public GrabToken(String promotionId, String customerId, long seq, long remaining) {
    this(promotionId, customerId, seq, remaining, null);
  }

  private GrabToken(String promotionId, String customerId, long seq, long remaining, String messageId) {
    this.promotionId = promotionId;
    this.customerId = customerId;
    this.seq = seq;
    this.remaining = remaining;
    this.payload = promotionId + SEP + customerId + SEP + seq + SEP + remaining;
    this.messageId = messageId;
  }

  public static GrabToken parse(String payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    String[] parts = payload.split(SEP, 4);
    if (parts.length != 4) {
      throw new IllegalArgumentException("Invalid grab payload: " + payload);
    }
    return new GrabToken(parts[0], parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3]));
  }

  public String getPromotionId() {
    return promotionId;
  }

  public String getCustomerId() {
    return customerId;
  }

  public long getSeq() {
    return seq;
  }

  public long getRemaining() {
    return remaining;
  }

  public String payload() {
    return payload;
  }

  public String getMessageId() {
    return messageId;
  }

  public GrabToken withMessageId(String messageId) {
    return new GrabToken(promotionId, customerId, seq, remaining, messageId);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof GrabToken)) {
      return false;
    }
    return payload.equals(((GrabToken) other).payload);
  }

  @Override
  public int hashCode() {
    return payload.hashCode();
  }
}
