package com.c2pa.portal;
import jakarta.persistence.*;
@Entity
public class ConfigurationRecord {
 @Id public Long id;
 @Version public Long revision;
 @Column(length=16000) public String draft;
 @Column(length=16000) public String active;
 public ConfigurationRecord() {}
}
