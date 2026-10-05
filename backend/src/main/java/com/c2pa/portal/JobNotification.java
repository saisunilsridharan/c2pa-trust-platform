package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class JobNotification {
 @Id public String id;
 @Column(nullable=false,unique=true) public String eventKey;
 public Long workspaceId;
 public String owner;
 public String jobId;
 public String outcome;
 public Instant createdAt;
 public Instant readAt;
 public JobNotification(){}
}
