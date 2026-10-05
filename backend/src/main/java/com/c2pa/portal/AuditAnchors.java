package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
@Service public class AuditAnchors {
 private final AuditAnchorSettingsRepository settings;private final AuditAnchorVersionRepository versions;private final AuditAnchorDeliveryRepository deliveries;private final AuditVerification verification;private final WorkspaceRepository workspaces;private final ImmutableAuditStorage storage;private final AuditService audit;private final ObjectMapper mapper;
 public AuditAnchors(AuditAnchorSettingsRepository settings,AuditAnchorVersionRepository versions,AuditAnchorDeliveryRepository deliveries,AuditVerification verification,WorkspaceRepository workspaces,ImmutableAuditStorage storage,AuditService audit,ObjectMapper mapper){this.settings=settings;this.versions=versions;this.deliveries=deliveries;this.verification=verification;this.workspaces=workspaces;this.storage=storage;this.audit=audit;this.mapper=mapper;}
 @Transactional public AuditAnchorDelivery queue(Long workspace,boolean scheduled)throws Exception{
  workspaces.lockById(workspace).orElseThrow();var s=settings.findById(workspace).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Configure and activate audit storage first"));
  if(s.activeVersion==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Activate audit storage first");
  var v=versions.findById(s.activeVersion).filter(p->p.workspaceId.equals(workspace)).orElseThrow();var c=storage.decode(v.configuration);
  if(!c.enabled())throw new ResponseStatusException(HttpStatus.CONFLICT,"Audit storage is disabled");
  if(scheduled && (s.nextCheckpointAt==null || s.nextCheckpointAt.isAfter(Instant.now())))return null;
  var chain=verification.verify(workspace);if(!chain.valid())throw new ResponseStatusException(HttpStatus.CONFLICT,"Audit integrity failed; checkpoint storage refused");
  var d=new AuditAnchorDelivery();d.id=UUID.randomUUID().toString();d.workspaceId=workspace;d.configuration=v.configuration;d.checkpoint=mapper.writeValueAsString(new AuditIntegrityController.Checkpoint("c2pa-audit-v1",workspace,chain.eventCount(),chain.hash(),Instant.now()));d.state="PENDING";d.createdAt=Instant.now();d.nextAttemptAt=d.createdAt;deliveries.saveAndFlush(d);
  s.nextCheckpointAt=Instant.now().plusSeconds(c.intervalMinutes()*60L);settings.saveAndFlush(s);audit.record(workspace,"AUDIT_CHECKPOINT_QUEUED",d.id);return d;
 }
 @Transactional public void dispatch(String id)throws Exception{
  var d=deliveries.lockById(id).orElseThrow();if(!d.state.equals("PENDING") || d.nextAttemptAt.isAfter(Instant.now()))return;d.attempts++;
  try{var receipt=storage.write(d.workspaceId,storage.decode(d.configuration),d.id,d.checkpoint);d.receipt=mapper.writeValueAsString(receipt);d.state="STORED";}catch(Exception e){if(d.attempts>=8)d.state="FAILED";else d.nextAttemptAt=Instant.now().plusSeconds(Math.min(3600,5L << d.attempts));}
  deliveries.saveAndFlush(d);audit.record(d.workspaceId,"AUDIT_CHECKPOINT_"+d.state,d.id);
 }
 @Transactional public void retry(Long workspace,String id,Long revision){var d=deliveries.lockById(id).filter(v->v.workspaceId.equals(workspace)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(!java.util.Objects.equals(d.revision,revision) || !d.state.equals("FAILED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a current failed checkpoint can be retried");d.state="PENDING";d.attempts=0;d.nextAttemptAt=Instant.now();deliveries.saveAndFlush(d);audit.record(workspace,"AUDIT_CHECKPOINT_RETRIED",id);}
}
