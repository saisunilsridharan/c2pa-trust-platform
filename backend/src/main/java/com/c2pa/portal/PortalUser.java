package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class PortalUser {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(unique=true,nullable=false) public String username;
 @Column(nullable=false) public String passwordHash;
 public String role;
 public boolean enabled=true;
 public int failedLogins;
 public Instant lockedUntil;
 public PortalUser(){}
}
