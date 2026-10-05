package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
class CertificateRenewalsTest {
 @Test void nestedBackgroundWorkspacesRestoreEvenAfterFailure() throws Exception {
  assertEquals(1L,WorkspaceContext.id());
  WorkspaceContext.call(9L,()->{assertEquals(9L,WorkspaceContext.id());assertThrows(IllegalStateException.class,()->WorkspaceContext.call(12L,()->{assertEquals(12L,WorkspaceContext.id());throw new IllegalStateException();}));assertEquals(9L,WorkspaceContext.id());return null;});
  assertEquals(1L,WorkspaceContext.id());
 }
 @Test void pausedExpiredAndReplacedLeasesCannotPublish() throws Exception {
  var plans=mock(CertificateRenewalPlanRepository.class);var options=mock(SigningOptionRepository.class);var workspaces=mock(WorkspaceRepository.class);
  when(workspaces.lockById(2L)).thenReturn(Optional.of(new Workspace()));
  var p=new CertificateRenewalPlan();p.workspaceId=2L;p.id="plan";p.leaseToken="lease";p.leaseUntil=Instant.now().plusSeconds(60);p.enabled=false;
  when(plans.lockById("plan")).thenReturn(Optional.of(p));
  var service=new CertificateRenewals(plans,options,mock(PrivateCaSettingsRepository.class),mock(PrivateCaVersionRepository.class),mock(HardwareSigning.class),mock(HardwareCertificateRequests.class),workspaces,mock(AuditService.class),new ObjectMapper());
  var claim=new CertificateRenewals.Claim("plan",2L,"lease","choice",0L,"issuer",null,"identity","fingerprint",null,6);
  assertFalse(service.complete(claim,"new"));p.enabled=true;p.leaseToken="replacement";assertFalse(service.complete(claim,"new"));service.failed(claim);assertEquals("replacement",p.leaseToken);
  p.leaseToken="lease";p.leaseUntil=Instant.now().minusSeconds(1);assertFalse(service.complete(claim,"new"));
  verifyNoInteractions(options);verify(plans,never()).saveAndFlush(any());
 }
 @Test void issuerChangesAndChoiceWithdrawalBlockPublication() throws Exception {
  var plans=mock(CertificateRenewalPlanRepository.class);var options=mock(SigningOptionRepository.class);var workspaces=mock(WorkspaceRepository.class);var settings=mock(PrivateCaSettingsRepository.class);var versions=mock(PrivateCaVersionRepository.class);var hardware=mock(HardwareSigning.class);
  when(workspaces.lockById(2L)).thenReturn(Optional.of(new Workspace()));
  var p=new CertificateRenewalPlan();p.id="plan";p.workspaceId=2L;p.providerVersion="issuer";p.currentChoiceId="choice";p.enabled=true;p.leaseToken="lease";p.leaseUntil=Instant.now().plusSeconds(60);
  when(plans.lockById("plan")).thenReturn(Optional.of(p));
  var service=new CertificateRenewals(plans,options,settings,versions,hardware,mock(HardwareCertificateRequests.class),workspaces,mock(AuditService.class),new ObjectMapper());
  var claim=new CertificateRenewals.Claim("plan",2L,"lease","choice",0L,"issuer",null,"identity","fingerprint",null,6);
  when(settings.findById(2L)).thenReturn(Optional.empty());
  assertFalse(service.complete(claim,"new"));assertFalse(p.enabled);assertEquals("BLOCKED",p.state);
  var active=new PrivateCaSettings();active.id=2L;active.activeVersion="issuer";when(settings.findById(2L)).thenReturn(Optional.of(active));
  var version=new PrivateCaVersion();version.id="issuer";version.workspaceId=2L;version.testedAt=Instant.now();version.configuration="{\"enabled\":true,\"validityHours\":48}";when(versions.findById("issuer")).thenReturn(Optional.of(version));
  var old=new SigningOption();old.id="choice";old.workspaceId=2L;old.revision=0L;old.keyPath="pkcs11:identity";old.enabled=false;when(options.findById("choice")).thenReturn(Optional.of(old));
  p.enabled=true;p.leaseToken="lease";p.leaseUntil=Instant.now().plusSeconds(60);
  assertFalse(service.complete(claim,"new"));assertFalse(p.enabled);assertEquals("BLOCKED",p.state);
  verify(options,never()).saveAndFlush(any());verifyNoInteractions(hardware);
 }
}
