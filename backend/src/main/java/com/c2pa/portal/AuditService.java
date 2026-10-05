package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
@Service
public class AuditService {
    private final AuditRepository repository;private final AuditHeadRepository heads;private final WorkspaceRepository workspaces;
    public AuditService(AuditRepository repository,AuditHeadRepository heads,WorkspaceRepository workspaces){this.repository=repository;this.heads=heads;this.workspaces=workspaces;}
    @Transactional public void record(String action,String reference){record(WorkspaceContext.id(),action,reference);}
    @Transactional public void record(Long workspaceId,String action,String reference){
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();Object actor=attributes==null?null:attributes.getAttribute("portal.actor",org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);record(workspaceId,actor==null?"system":actor.toString(),action,reference);
    }
    @Transactional public void record(Long workspaceId,String actor,String action,String reference){
        AuditHead head=initialize(workspaceId);
        AuditEvent event=new AuditEvent();
        event.workspaceId=workspaceId;
        event.createdAt=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        event.actor=actor;event.action=action;event.reference=reference;append(head,event);heads.saveAndFlush(head);
    }
    private void append(AuditHead head,AuditEvent event){event.chainIndex=++head.eventCount;event.previousHash=head.hash;event.hash=AuditHasher.hash(event);head.hash=event.hash;repository.saveAndFlush(event);}
    @Transactional public AuditHead initialize(Long workspace){
        workspaces.lockById(WorkspaceContext.DEFAULT).orElseThrow();var existing=heads.findById(workspace);if(existing.isPresent())return existing.get();AuditHead head=new AuditHead();head.id=workspace;head.hash=AuditHasher.GENESIS;heads.saveAndFlush(head);
        int page=0;while(true){var batch=repository.findByWorkspaceIdOrderByIdAsc(workspace,PageRequest.of(page++,500));for(var event:batch){if(event.hash!=null || event.chainIndex!=null)throw new IllegalStateException("Audit head missing for existing chain");event.legacyImported=true;append(head,event);}if(batch.size()<500)break;}return heads.saveAndFlush(head);
    }
}
