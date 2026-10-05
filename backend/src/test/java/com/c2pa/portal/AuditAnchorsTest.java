package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AuditAnchorsTest {
 @Test void exhaustedWritesPersistFailureAndRetryPreservesCapturedCheckpointAndProvider()throws Exception{
  var settings=mock(AuditAnchorSettingsRepository.class);var versions=mock(AuditAnchorVersionRepository.class);var deliveries=mock(AuditAnchorDeliveryRepository.class);var verification=mock(AuditVerification.class);var workspaces=mock(WorkspaceRepository.class);var storage=mock(ImmutableAuditStorage.class);var audit=mock(AuditService.class);
  var service=new AuditAnchors(settings,versions,deliveries,verification,workspaces,storage,audit,new com.fasterxml.jackson.databind.ObjectMapper());
  var d=new AuditAnchorDelivery();d.id="fixture";d.workspaceId=7L;d.revision=4L;d.state="PENDING";d.attempts=7;d.nextAttemptAt=Instant.EPOCH;d.configuration="captured-provider";d.checkpoint="captured-checkpoint";
  when(deliveries.lockById(d.id)).thenReturn(Optional.of(d));when(storage.write(eq(7L),any(),eq(d.id),eq(d.checkpoint))).thenThrow(new IllegalStateException("Provider unavailable"));
  service.dispatch(d.id);assertEquals("FAILED",d.state);assertEquals(8,d.attempts);assertNull(d.receipt);verify(deliveries).saveAndFlush(d);verify(audit).record(7L,"AUDIT_CHECKPOINT_FAILED",d.id);
  assertThrows(ResponseStatusException.class,()->service.retry(8L,d.id,4L));assertEquals("FAILED",d.state);
  service.retry(7L,d.id,4L);assertEquals("PENDING",d.state);assertEquals(0,d.attempts);assertEquals("captured-provider",d.configuration);assertEquals("captured-checkpoint",d.checkpoint);
 }
 @Test void invalidAuditChainCannotBeQueued()throws Exception{
  var settings=mock(AuditAnchorSettingsRepository.class);var versions=mock(AuditAnchorVersionRepository.class);var deliveries=mock(AuditAnchorDeliveryRepository.class);var verification=mock(AuditVerification.class);var workspaces=mock(WorkspaceRepository.class);var storage=mock(ImmutableAuditStorage.class);var audit=mock(AuditService.class);
  var service=new AuditAnchors(settings,versions,deliveries,verification,workspaces,storage,audit,new com.fasterxml.jackson.databind.ObjectMapper());
  var s=new AuditAnchorSettings();s.id=7L;s.activeVersion="provider";var v=new AuditAnchorVersion();v.workspaceId=7L;v.configuration="configuration";
  when(workspaces.lockById(7L)).thenReturn(Optional.of(new Workspace()));when(settings.findById(7L)).thenReturn(Optional.of(s));when(versions.findById("provider")).thenReturn(Optional.of(v));when(storage.decode(v.configuration)).thenReturn(new ImmutableAuditStorage.Configuration(true,null,30,60));when(verification.verify(7L)).thenReturn(new AuditIntegrityController.Integrity(false,3,0,null,"Changed chain"));
  assertThrows(ResponseStatusException.class,()->service.queue(7L,false));verifyNoInteractions(deliveries);verifyNoInteractions(audit);
 }
}
