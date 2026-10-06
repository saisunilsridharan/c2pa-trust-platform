package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class PublicTrustVersion {
 @Id public String id;@Column(nullable=false) public Long workspaceId;
 @Column(nullable=false,length=16000) public String configuration;
 @Column(length=1000000) public String signerList,tsaList;
 public Instant fetchedAt,testedAt,createdAt;
}
