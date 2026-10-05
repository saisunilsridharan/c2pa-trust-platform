package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;
@RestController @RequestMapping("/api/v1/admin/audit-integrity")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class AuditIntegrityController {
 private final AuditRepository events;private final AuditService audit;private final ObjectMapper mapper;
 public AuditIntegrityController(AuditRepository events,AuditService audit,ObjectMapper mapper){this.events=events;this.audit=audit;this.mapper=mapper;}
 public record Integrity(boolean valid,long eventCount,long legacyImported,String hash,String message){}
 public record Checkpoint(String format,Long workspaceId,long eventCount,String hash,Instant exportedAt){}
 private Integrity verify(){
  var head=audit.initialize(WorkspaceContext.id());long count=0,legacy=0;String hash=AuditHasher.GENESIS;int page=0;
  while(true){var batch=events.findByWorkspaceIdOrderByIdAsc(WorkspaceContext.id(),PageRequest.of(page++,500));for(var event:batch){count++;if(event.legacyImported)legacy++;if(!Objects.equals(event.chainIndex,count) || !Objects.equals(event.previousHash,hash) || !Objects.equals(event.hash,AuditHasher.hash(event)))return new Integrity(false,count,legacy,null,"Audit content, order or hash chain changed");hash=event.hash;}if(batch.size()<500)break;}
  boolean valid=count==head.eventCount && hash.equals(head.hash);return new Integrity(valid,count,legacy,valid?hash:null,valid?"Chain verified. Compare a previously exported checkpoint to detect a complete database rewrite.":"Audit head disagrees with recorded events");
 }
 @GetMapping @Transactional public Integrity get(){return verify();}
 @GetMapping("/checkpoint") @Transactional public ResponseEntity<byte[]> export()throws Exception{var result=verify();if(!result.valid())throw new ResponseStatusException(HttpStatus.CONFLICT,"Audit integrity failed; checkpoint export refused");var checkpoint=new Checkpoint("c2pa-audit-v1",WorkspaceContext.id(),result.eventCount(),result.hash(),Instant.now());return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"audit-checkpoint.json\"").body(mapper.writeValueAsBytes(checkpoint));}
 @PostMapping("/checkpoint/verify") @Transactional public Integrity compare(@RequestBody Checkpoint checkpoint){
  if(checkpoint==null || !"c2pa-audit-v1".equals(checkpoint.format()) || !WorkspaceContext.id().equals(checkpoint.workspaceId()) || checkpoint.eventCount()<0 || checkpoint.hash()==null || !checkpoint.hash().matches("[a-f0-9]{64}") || checkpoint.exportedAt()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid checkpoint or workspace");var result=verify();if(!result.valid())return result;
  boolean same=checkpoint.eventCount()<=result.eventCount() && (checkpoint.eventCount()==0?checkpoint.hash().equals(AuditHasher.GENESIS):events.findByWorkspaceIdAndChainIndex(WorkspaceContext.id(),checkpoint.eventCount()).map(e->e.hash.equals(checkpoint.hash())).orElse(false));return new Integrity(same,result.eventCount(),result.legacyImported(),result.hash(),same?"Current chain agrees with the supplied earlier checkpoint":"Earlier checkpoint disagrees: events were changed, rewritten or removed");
 }
}
