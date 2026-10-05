package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class AuditAnchorSettings {
 @Id public Long id;
 @Version public Long revision;
 public String draftVersion;
 public String activeVersion;
 public Instant nextCheckpointAt;
}
