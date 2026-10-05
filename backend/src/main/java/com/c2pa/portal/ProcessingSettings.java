package com.c2pa.portal;
import jakarta.persistence.*;
@Entity public class ProcessingSettings {
 @Id public Long id;
 @Version public Long revision;
 public int workerTimeoutSeconds=45;
 public int maxAttempts=10;
 @Column(nullable=false) public int maxMemoryMb=1024;
 @Column(nullable=false) public int maxCpuSeconds=45;
 @Column(nullable=false,length=32) public String sandboxMode="LIMITED";
 public ProcessingSettings(){}
}
