package com.c2pa.portal;
import jakarta.persistence.*;
@Entity
public class AssetStorage {
 @Id public Long id=1L;
 @Version public Long revision;
 public int retentionDays=30;
 public AssetStorage(){}
}
