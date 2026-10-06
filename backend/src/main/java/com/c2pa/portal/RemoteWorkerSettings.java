package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity public class RemoteWorkerSettings {
 @Id public Long id;
 @Version public Long revision;
 @Column(nullable=false) public boolean remoteQueuedSigning;
 @Column(nullable=false) public boolean agentEnabled;
 @Column(length=16000) public String agentConfiguration;
 public Instant agentTestedAt;
}
