package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class EncryptedCredential {
 @Id public String id;
 @Column(nullable=false) public Long workspaceId;
 @Column(nullable=false,length=80) public String label;
 @Column(nullable=false,length=64000) public String sealed;
 @Column(nullable=false,length=64) public String keyFingerprint;
 public Instant createdAt;
 public EncryptedCredential(){}
}
