package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class CertificateRenewalPlan {
 @Id public String id;
 @Version public Long revision;
 @Column(nullable=false) public Long workspaceId;
 @Column(nullable=false) public String providerVersion;
 @Column(nullable=false) public String currentChoiceId;
 public Long currentChoiceRevision;
 @Column(nullable=false,length=2000) public String subjectConfiguration;
 public int renewBeforeHours;
 public boolean enabled;
 public String state;
 public int attempts;
 public Instant nextCheckAt;
 public String leaseToken;
 public Instant leaseUntil;
 public Instant createdAt;
 public Instant lastRenewedAt;
 public String lastIdentityId;
 @Column(length=300) public String error;
}
