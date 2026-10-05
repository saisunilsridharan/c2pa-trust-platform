package com.c2pa.portal;
import org.springframework.stereotype.Service;
@Service
public class AuditService {
    private final AuditRepository repository;
    public AuditService(AuditRepository repository){this.repository=repository;}
    public void record(String action,String reference){record(WorkspaceContext.id(),action,reference);}
    public void record(Long workspaceId,String action,String reference){
        AuditEvent event=new AuditEvent();
        event.workspaceId=workspaceId;
        event.createdAt=java.time.Instant.now();
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        Object actor=attributes==null?null:attributes.getAttribute("portal.actor",org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        event.actor=actor==null?"system":actor.toString();
        event.action=action;event.reference=reference;repository.save(event);
    }
}
