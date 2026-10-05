package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class WebhookVersion {
 @Id public String id;
 public Long workspaceId;
 @Column(length=16000) public String configuration;
 public Instant createdAt;
 public Instant testedAt;
 public WebhookVersion(){}
}
