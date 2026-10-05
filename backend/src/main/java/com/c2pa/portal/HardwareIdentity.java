package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class HardwareIdentity {
 @Id public String id;
 @Column(nullable=false) public Long workspaceId;
 @Column(nullable=false,length=16000) public String configuration;
 @Column(nullable=false,length=2000) public String certificatePath;
 @Column(nullable=false,length=64) public String fingerprint;
 public Instant createdAt;
 public Instant testedAt;
}
