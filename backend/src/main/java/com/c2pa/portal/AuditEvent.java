package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class AuditEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Instant createdAt;
    public String actor;
    public String action;
    public String reference;
    public AuditEvent() {}
}
