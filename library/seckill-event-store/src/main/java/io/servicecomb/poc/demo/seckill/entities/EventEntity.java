package io.servicecomb.poc.demo.seckill.entities;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "sec_kill_event", uniqueConstraints = {
    @UniqueConstraint(name = "uk_grab_customer", columnNames = {"promotionId", "customerId"})
})
public class EventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private int id;

  @Column(unique = true)
  private String eventId;

  private long seq;

  private String type;

  private String promotionId;

  private String customerId;

  private long occurredAt;

  @Column(length = 8000)
  private String content;

  public EventEntity() {
  }

  public EventEntity(String type, String promotionId, String content) {
    this(UUID.randomUUID().toString(), 0L, type, promotionId, null, content, System.currentTimeMillis());
  }

  public EventEntity(String eventId, long seq, String type, String promotionId, String customerId, String content,
      long occurredAt) {
    this.eventId = eventId;
    this.seq = seq;
    this.type = type;
    this.promotionId = promotionId;
    this.customerId = customerId;
    this.content = content;
    this.occurredAt = occurredAt;
  }

  public int getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public long getSeq() {
    return seq;
  }

  public String getType() {
    return type;
  }

  public String getPromotionId() {
    return promotionId;
  }

  public String getCustomerId() {
    return customerId;
  }

  public long getOccurredAt() {
    return occurredAt;
  }

  public String getContent() {
    return content;
  }
}
