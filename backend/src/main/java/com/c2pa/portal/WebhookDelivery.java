package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class WebhookDelivery {
 @Id public String id;
 @Version public Long revision;
 @Column(unique=true,nullable=false) public String eventKey;
 public Long workspaceId;
 @Column(length=16000) public String configuration;
 @Column(length=8000) public String payload;
 public String state;
 public int attempts;
 public Instant createdAt;
 public Instant nextAttemptAt;
 public Integer statusCode;
 public WebhookDelivery(){}
}
