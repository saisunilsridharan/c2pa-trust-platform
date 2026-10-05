package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class AccountFactor {
 @Id public Long id;
 public String activeCredential;
 public String pendingCredential;
 public Instant pendingExpiresAt;
 public boolean enabled;
 public long lastStep=-1;
 @Column(length=4000) public String recoveryHashes;
 public String accountRecoveryHash;
 public AccountFactor(){}
}
