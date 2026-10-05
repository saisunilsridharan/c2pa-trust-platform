package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class AuditAnchorDelivery {
 @Id public String id;
 @Version public Long revision;
 @Column(nullable=false) public Long workspaceId;
 @Column(nullable=false,length=80000) public String configuration;
 @Column(nullable=false,length=4000) public String checkpoint;
 @Column(nullable=false) public String state;
 public int attempts;
 public Instant createdAt;
 public Instant nextAttemptAt;
 @Column(length=10000) public String receipt;
}
