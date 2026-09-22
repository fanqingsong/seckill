package io.servicecomb.poc.demo.seckill.dto;

public class EventMessageDto {

  protected String eventId;
  protected String promotionId;
  protected long seq;
  protected String type;
  protected long occurredAt;
  protected String content;
  protected String customerId;

  public EventMessageDto() {
  }

  public EventMessageDto(String type, String promotionId, String content) {
    this(null, promotionId, 0L, type, System.currentTimeMillis(), content, null);
  }

  public EventMessageDto(String eventId, String promotionId, long seq, String type, long occurredAt, String content,
      String customerId) {
    this.eventId = eventId;
    this.promotionId = promotionId;
    this.seq = seq;
    this.type = type;
    this.occurredAt = occurredAt;
    this.content = content;
    this.customerId = customerId;
  }

  public String getEventId() {
    return eventId;
  }

  public String getPromotionId() {
    return promotionId;
  }

  public long getSeq() {
    return seq;
  }

  public String getType() {
    return type;
  }

  public long getOccurredAt() {
    return occurredAt;
  }

  public String getContent() {
    return content;
  }

  public String getCustomerId() {
    return customerId;
  }
}
