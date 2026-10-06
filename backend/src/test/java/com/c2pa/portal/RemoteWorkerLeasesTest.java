package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:remote-leases","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-","portal.jobs.recovery.schedule=-","portal.webhooks.schedule=-"})
class RemoteWorkerLeasesTest {
 @Autowired RemoteWorkerRepository workers;@Autowired RemoteWorkerSecurity security;@Autowired RemoteWorkerLeases remote;@Autowired JobRepository jobs;@Autowired JobLeases local;
 private RemoteWorker worker(Long workspace){var w=new RemoteWorker();w.id=UUID.randomUUID().toString();w.workspaceId=workspace;w.label="Fixture";w.createdAt=Instant.now();w.lastSeenAt=Instant.now();w.enabled=true;w.testedAt=Instant.now();security.issue(w,1);return workers.saveAndFlush(w);}
 private SigningJob job(Long workspace){var j=new SigningJob();j.id=UUID.randomUUID().toString();j.workspaceId=workspace;j.owner="remote-fixture";j.state="QUEUED";j.remoteQueued=true;j.keyPath="pkcs11:"+UUID.randomUUID();j.createdAt=Instant.now();return jobs.saveAndFlush(j);}
 @Test void scopeConcurrentClaimsLocalSeparationAndAttemptRecovery()throws Exception{
  var w=worker(10L);var other=worker(11L);var j=job(10L);assertTrue(local.claim(j.id).isEmpty());assertTrue(remote.claim(other.id).isEmpty());
  try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return remote.claim(w.id);});var b=pool.submit(()->{gate.await();return remote.claim(w.id);});gate.countDown();assertEquals(1,(a.get(10,TimeUnit.SECONDS).isPresent()?1:0)+(b.get(10,TimeUnit.SECONDS).isPresent()?1:0));}
  var first=jobs.findById(j.id).orElseThrow();assertEquals(w.id,first.remoteWorkerId);assertTrue(remote.claim(w.id).isEmpty());assertThrows(ResponseStatusException.class,()->remote.access(other.id,j.id,first.leaseToken));
  first.leaseUntil=Instant.now().minusSeconds(1);jobs.saveAndFlush(first);assertThrows(ResponseStatusException.class,()->remote.access(w.id,j.id,first.leaseToken));local.recover(j.id);
  var second=remote.claim(w.id).orElseThrow();assertNotEquals(first.leaseToken,second.leaseToken);assertEquals(2,second.attempts);assertThrows(ResponseStatusException.class,()->remote.access(w.id,j.id,first.leaseToken));
  var withdrawn=workers.findById(w.id).orElseThrow();withdrawn.enabled=false;workers.saveAndFlush(withdrawn);assertThrows(ResponseStatusException.class,()->remote.access(w.id,j.id,second.leaseToken));
 }
 @Test void nonceCallbackBudgetAndPublicationGuard()throws Exception{
  var w=worker(12L);var j=job(12L);var claimed=remote.claim(w.id).orElseThrow();assertThrows(ResponseStatusException.class,()->remote.callback(w.id,j.id,UUID.randomUUID().toString()));
  for(int n=1;n<=4;n++)assertEquals(n,remote.callback(w.id,j.id,claimed.leaseToken).remoteCallbacks);
  assertThrows(ResponseStatusException.class,()->remote.callback(w.id,j.id,claimed.leaseToken));
  claimed.state="COMPLETED";assertThrows(Exception.class,()->remote.finish(w.id,claimed,()->{throw new java.io.IOException("Fixture storage failed");}));assertEquals("RUNNING",jobs.findById(j.id).orElseThrow().state);
  remote.finish(w.id,claimed,()->{});assertEquals("COMPLETED",jobs.findById(j.id).orElseThrow().state);assertThrows(ResponseStatusException.class,()->remote.finish(w.id,claimed,()->{fail("Duplicate publication executed");}));
 }
 @Test void pairingRotationExpiryAndInFlightAuthenticationGeneration()throws Exception{
  var w=worker(13L);String first=security.issue(w,1);workers.saveAndFlush(w);assertTrue(security.authenticate(first).isPresent());assertTrue(security.authenticate(first+"x").isEmpty());assertTrue(security.authenticate(UUID.randomUUID().toString()).isEmpty());
  var j=job(13L);var claimed=remote.claim(w.id).orElseThrow();String oldHash=w.tokenHash;var rotated=workers.findById(w.id).orElseThrow();security.issue(rotated,1);workers.saveAndFlush(rotated);assertTrue(security.authenticate(first).isEmpty());
  var request=new org.springframework.mock.web.MockHttpServletRequest();request.setAttribute("portal.workerTokenHash",oldHash);org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
  try{assertThrows(ResponseStatusException.class,()->remote.access(w.id,j.id,claimed.leaseToken));}finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
  var expired=workers.findById(w.id).orElseThrow();expired.expiresAt=Instant.now().minusSeconds(1);workers.saveAndFlush(expired);assertThrows(ResponseStatusException.class,()->remote.claim(w.id));
 }
 @Test void pausedWorkerCanOnlyClaimItsExplicitProbe()throws Exception{
  var w=worker(14L);w.enabled=false;w.testedAt=null;workers.saveAndFlush(w);var ordinary=job(14L);assertTrue(remote.claim(w.id).isEmpty());
  var probe=job(14L);probe.remoteTarget=w.id;jobs.saveAndFlush(probe);assertTrue(remote.claim(w.id).isEmpty());var paused=workers.findById(w.id).orElseThrow();paused.probeJobId=probe.id;workers.saveAndFlush(paused);
  var claim=remote.claim(w.id).orElseThrow();assertEquals(probe.id,claim.id);claim.state="COMPLETED";remote.finish(w.id,claim,()->{});assertNotNull(workers.findById(w.id).orElseThrow().testedAt);assertFalse(workers.findById(w.id).orElseThrow().enabled);assertEquals("QUEUED",jobs.findById(ordinary.id).orElseThrow().state);
 }
}
