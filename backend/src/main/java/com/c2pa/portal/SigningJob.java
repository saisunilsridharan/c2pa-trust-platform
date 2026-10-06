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
 @Column(nullable=false,columnDefinition="integer default 10") public int maxAttempts=10;
 @Column(nullable=false,columnDefinition="integer default 45") public int workerTimeoutSeconds=45;
 public String leaseToken;
 public Instant leaseUntil;
 public String resultAttempt;
 @Column(nullable=false) public boolean remoteQueued;
 public String remoteWorkerId,remoteTarget;
 @Column(nullable=false) public int remoteCallbacks;
 @Column(length=8000) public String attemptDirectories;
 @Column(length=16000) public String storageSnapshot;
 @Column(length=80000) public String trustSnapshot;
 @Column(length=80000) public String timestampSnapshot;
 @Column(nullable=false,columnDefinition="bigint default 1") public Long workspaceId=1L;
 @Column(nullable=false) public int maxMemoryMb=1024;
 @Column(nullable=false) public int maxCpuSeconds=45;
 @Column(nullable=false,length=32) public String sandboxMode="LIMITED";
 public SigningJob(){}
}
