package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class SigningOption {
 @Id public String id;
 @Version public Long revision;
 @Column(nullable=false) public Long workspaceId;
 @Column(nullable=false,length=120) public String label;
 @Column(nullable=false,length=16000) public String settings;
 @Column(nullable=false) public Long profileRevision;
 @Column(nullable=false,length=64) public String fingerprint;
 @Column(nullable=false,length=2000) public String certificatePath;
 @Column(nullable=false,length=2000) public String keyPath;
 public boolean development;
 public boolean enabled=true;
 public Instant createdAt;
}
