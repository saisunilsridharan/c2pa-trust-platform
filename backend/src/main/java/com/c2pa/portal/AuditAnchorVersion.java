package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class AuditAnchorVersion {
 @Id public String id;
 @Column(nullable=false) public Long workspaceId;
 @Column(nullable=false,length=80000) public String configuration;
 public Instant createdAt;
 public Instant testedAt;
}
