package com.c2pa.portal;
import jakarta.persistence.*;
@Entity public class AuditHead {
 @Id public Long id;
 @Version public Long revision;
 public long eventCount;
 public String hash;
 public AuditHead(){}
}
