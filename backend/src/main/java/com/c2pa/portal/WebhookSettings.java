package com.c2pa.portal;
import jakarta.persistence.*;
@Entity public class WebhookSettings {
 @Id public Long id;
 @Version public Long revision;
 public String draftVersion;
 public String activeVersion;
 public WebhookSettings(){}
}
