package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class AuthenticationRateBucket {
 @Id @Column(length=64) public String id;
 public Instant startedAt;
 public int attempts;
}
