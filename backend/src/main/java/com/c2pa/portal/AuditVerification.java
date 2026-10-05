package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.util.Objects;
@Service public class AuditVerification {
 private final AuditRepository events;private final AuditService audit;
 public AuditVerification(AuditRepository events,AuditService audit){this.events=events;this.audit=audit;}
 @Transactional
 public AuditIntegrityController.Integrity verify(Long workspace){
  var head=audit.initialize(workspace);long count=0,legacy=0;String hash=AuditHasher.GENESIS;int page=0;
  while(true){var batch=events.findByWorkspaceIdOrderByIdAsc(workspace,PageRequest.of(page++,500));for(var event:batch){count++;if(event.legacyImported)legacy++;if(!Objects.equals(event.chainIndex,count) || !Objects.equals(event.previousHash,hash) || !Objects.equals(event.hash,AuditHasher.hash(event)))return new AuditIntegrityController.Integrity(false,count,legacy,null,"Audit content, order or hash chain changed");hash=event.hash;}if(batch.size()<500)break;}
  boolean valid=count==head.eventCount && hash.equals(head.hash);return new AuditIntegrityController.Integrity(valid,count,legacy,valid?hash:null,valid?"Chain verified. Compare a previously exported checkpoint to detect a complete database rewrite.":"Audit head disagrees with recorded events");
 }
}
