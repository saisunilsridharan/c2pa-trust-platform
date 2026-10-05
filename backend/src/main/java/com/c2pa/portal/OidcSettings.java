package com.c2pa.portal;
import jakarta.persistence.*;
@Entity public class OidcSettings {
 @Id public Long id=1L;
 @Version public Long revision;
 public String draftVersion;
 public String activeVersion;
 public OidcSettings(){}
}
