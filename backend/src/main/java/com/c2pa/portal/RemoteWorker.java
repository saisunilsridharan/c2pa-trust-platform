package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class RemoteWorker {
 @Id public String id;
 @Version public Long revision;
 @Column(nullable=false) public Long workspaceId;
 @Column(nullable=false,length=120) public String label;
 @Column(nullable=false,length=64) public String tokenHash;
 @Column(nullable=false) public Instant expiresAt;
 @Column(nullable=false) public boolean enabled;
 public Instant createdAt,lastSeenAt,testedAt;
 public String probeJobId;
 @Column(nullable=false) public boolean namespaceAvailable;
}
