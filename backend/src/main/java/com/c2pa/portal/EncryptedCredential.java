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
 @Column(nullable=false,length=32,columnDefinition="varchar(32) default 'INTEGRATION'") public String kind="INTEGRATION";
 public EncryptedCredential(){}
}
