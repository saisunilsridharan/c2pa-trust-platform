package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class StorageVersion {
 @Id public String id;
 @Column(nullable=false) public Long workspaceId;
 @Column(length=16000,nullable=false) public String configuration;
 public Instant createdAt;
 public Instant testedAt;
 public StorageVersion(){}
}
