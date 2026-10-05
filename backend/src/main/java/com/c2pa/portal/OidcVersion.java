package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class OidcVersion {
 @Id public String id;
 @Column(length=16000) public String configuration;
 public Instant createdAt;
 public Instant testedAt;
 public OidcVersion(){}
}
