package com.c2pa.portal;
import jakarta.persistence.*;
@Entity public class AuthenticationRateSettings {
 @Id public Long id;
 @Version public Long revision;
 public int windowSeconds=60;
 public int perAddressLimit=30;
 public int globalLimit=1000;
 @Column(length=8000) public String trustedProxyCidrs="";
}
