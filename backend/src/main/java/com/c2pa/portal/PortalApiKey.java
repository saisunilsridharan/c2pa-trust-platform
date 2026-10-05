package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class PortalApiKey {
 @Id public String id;
 @Column(nullable=false,unique=true,length=64) public String tokenHash;
 public Long userId;
 public Long workspaceId;
 public String label;
 public String scopes;
 public Instant createdAt;
 public Instant expiresAt;
 public boolean revoked;
 public PortalApiKey(){}
}
