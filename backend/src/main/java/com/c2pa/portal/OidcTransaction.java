package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class OidcTransaction {
 @Id public String id;
 public String browserHash;
 public String versionId;
 public String proofCredential;
 public Instant expiresAt;
 public boolean consumed;
 public OidcTransaction(){}
}
