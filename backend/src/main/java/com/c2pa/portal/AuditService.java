package com.c2pa.portal;
import org.springframework.stereotype.Service;
@Service
public class AuditService {
    private final AuditRepository repository;
    public AuditService(AuditRepository repository){this.repository=repository;}
    public void record(String action,String reference){
        AuditEvent event=new AuditEvent();
        event.createdAt=java.time.Instant.now();event.actor="local-administrator";
        event.action=action;event.reference=reference;repository.save(event);
    }
}
