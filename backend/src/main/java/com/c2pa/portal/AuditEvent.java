package com.c2pa.portal;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(uniqueConstraints=@UniqueConstraint(columnNames={"workspaceId","chainIndex"}))
public class AuditEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Instant createdAt;
    public String actor;
    public String action;
    public String reference;
    public Long chainIndex;
    public String previousHash;
    public String hash;
    @Column(nullable=false,columnDefinition="boolean default false") public boolean legacyImported;
    @Column(nullable=false,columnDefinition="bigint default 1") public Long workspaceId=1L;
 public AuditEvent() {}
}
