package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class SigningJob {
 @Id public String id;
 @Version public Long revision;
 @Column(unique=true) public String requestKey;
 public String requestDigest;
 public String owner;
 public String state;
 public String format;
 public String title;
 public String fingerprint;
 public String certificatePath;
 public String keyPath;
 public String error;
 public Instant createdAt;
 public Instant completedAt;
 public int attempts;
 @Column(nullable=false,columnDefinition="bigint default 1") public Long workspaceId=1L;
 public SigningJob(){}
}
