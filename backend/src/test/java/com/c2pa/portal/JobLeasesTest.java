package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:leases","portal.jobs.schedule=-","portal.jobs.cleanup.schedule=-","portal.jobs.recovery.schedule=-","portal.webhooks.schedule=-"})
class JobLeasesTest {
 @Autowired JobRepository jobs;@Autowired JobLeases leases;@Autowired JobCompletion completion;@Autowired NotificationRepository notifications;
 @Test void oneClaimLiveLeasePreservationStaleRejectionAndAttemptLimit()throws Exception{
  SigningJob job=new SigningJob();job.id=UUID.randomUUID().toString();job.state="QUEUED";job.owner="lease-fixture";job.maxAttempts=2;job.createdAt=Instant.now();jobs.saveAndFlush(job);String id=job.id;
  try(var pool=Executors.newFixedThreadPool(2)){CountDownLatch start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return leases.claim(id);});var b=pool.submit(()->{start.await();return leases.claim(id);});start.countDown();var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);assertEquals(1,(first.isPresent()?1:0)+(second.isPresent()?1:0));}
  var original=jobs.findById(id).orElseThrow();assertEquals(1,original.attempts);leases.recover(id);assertEquals(original.leaseToken,jobs.findById(id).orElseThrow().leaseToken);
  var expired=jobs.findById(id).orElseThrow();expired.leaseUntil=Instant.now().minusSeconds(1);jobs.saveAndFlush(expired);leases.recover(id);assertEquals("QUEUED",jobs.findById(id).orElseThrow().state);var next=leases.claim(id).orElseThrow();assertNotEquals(original.leaseToken,next.leaseToken);assertEquals(2,next.attempts);assertEquals(2,next.attemptDirectories.split(",").length);
  original.state="COMPLETED";original.completedAt=Instant.now();assertThrows(IllegalStateException.class,()->completion.finish(original));assertEquals("RUNNING",jobs.findById(id).orElseThrow().state);assertEquals(0,notifications.count());
  next.leaseUntil=Instant.now().minusSeconds(1);jobs.saveAndFlush(next);leases.recover(id);assertEquals("FAILED",jobs.findById(id).orElseThrow().state);assertEquals(1,notifications.count());assertTrue(leases.claim(id).isEmpty());
 }
}
