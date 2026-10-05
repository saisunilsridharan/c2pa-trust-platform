package com.c2pa.portal;
import jakarta.persistence.*;
@Entity public class ProcessingSettings {
 @Id public Long id;
 @Version public Long revision;
 public int workerTimeoutSeconds=45;
 public int maxAttempts=10;
 public ProcessingSettings(){}
}
