package com.c2pa.portal;
import jakarta.persistence.*;
@Entity public class PrivateCaVersion {
 @Id public String id;
 @Column(nullable=false) public Long workspaceId;
 @Column(nullable=false,length=80000) public String configuration;
 public java.time.Instant createdAt;
 public java.time.Instant testedAt;
}
