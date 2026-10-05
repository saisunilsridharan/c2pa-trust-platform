package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class LoginSession {
 @Id public String tokenHash;
 public Long userId;
 public Instant expiresAt;
 public LoginSession(){}
}
