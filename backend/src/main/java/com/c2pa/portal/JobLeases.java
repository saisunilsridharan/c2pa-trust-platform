package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
@Service public class JobLeases {
 private final JobRepository jobs;private final JobCompletion completion;
 public JobLeases(JobRepository jobs,JobCompletion completion){this.jobs=jobs;this.completion=completion;}
 @Transactional public Optional<SigningJob> claim(String id){
  var found=jobs.lockById(id);if(found.isEmpty() || !found.get().state.equals("QUEUED") || found.get().remoteQueued)return Optional.empty();var job=found.get();
  job.state="RUNNING";job.attempts++;job.leaseToken=UUID.randomUUID().toString();job.leaseUntil=Instant.now().plusSeconds(job.workerTimeoutSeconds+180L);job.attemptDirectories=job.attemptDirectories==null?job.leaseToken:job.attemptDirectories+","+job.leaseToken;return Optional.of(jobs.saveAndFlush(job));
 }
 @Transactional public void recover(String id){var found=jobs.lockById(id);if(found.isEmpty())return;var job=found.get();if(job.state.equals("RUNNING") && (job.leaseUntil==null || job.leaseUntil.isBefore(Instant.now()))){job.leaseToken=null;job.leaseUntil=null;if(job.attempts>=job.maxAttempts){job.state="FAILED";job.completedAt=Instant.now();job.error="Processing was interrupted and the attempt limit was exhausted.";completion.finish(job);}else{job.state="QUEUED";jobs.saveAndFlush(job);}}}
}
