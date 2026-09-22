package io.servicecomb.poc.demo.seckill.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox")
public class OutboxEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String eventId;

  private String kafkaKey;

  @Column(length = 8000)
  private String payload;

  private long createdAt;

  private boolean published;

  public OutboxEntity() {
  }

  public OutboxEntity(String eventId, String kafkaKey, String payload) {
    this.eventId = eventId;
    this.kafkaKey = kafkaKey;
    this.payload = payload;
    this.createdAt = System.currentTimeMillis();
    this.published = false;
  }

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public String getKafkaKey() {
    return kafkaKey;
  }

  public String getPayload() {
    return payload;
  }

  public long getCreatedAt() {
    return createdAt;
  }

  public boolean isPublished() {
    return published;
  }

  public void markPublished() {
    this.published = true;
  }
}
