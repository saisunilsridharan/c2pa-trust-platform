package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
@Service
public class JobCompletion {
 private final JobRepository jobs;private final AuditService audit;private final NotificationRepository notifications;
 private final WebhookService webhooks;
 public JobCompletion(JobRepository jobs,AuditService audit,NotificationRepository notifications,WebhookService webhooks){this.jobs=jobs;this.audit=audit;this.notifications=notifications;this.webhooks=webhooks;}
 @Transactional public void finish(SigningJob job){
  if(!java.util.Set.of("COMPLETED","FAILED").contains(job.state))throw new IllegalArgumentException();
  String eventKey=job.id+":"+job.attempts;
  if(notifications.existsByEventKey(eventKey))return;
  var current=jobs.lockById(job.id).orElseThrow();if(job.leaseToken!=null && (!java.util.Objects.equals(current.leaseToken,job.leaseToken) || !current.state.equals("RUNNING") || current.leaseUntil.isBefore(Instant.now())))throw new IllegalStateException("Worker lease expired or changed");job.leaseUntil=null;jobs.saveAndFlush(job);
  JobNotification notification=new JobNotification();notification.id=UUID.randomUUID().toString();notification.eventKey=eventKey;notification.workspaceId=job.workspaceId;notification.owner=job.owner;notification.jobId=job.id;notification.outcome=job.state;notification.createdAt=Instant.now();notifications.saveAndFlush(notification);
  audit.record(job.workspaceId,job.owner,"SIGNING_JOB_"+job.state,job.id);
  try{webhooks.enqueue(job);}catch(Exception e){throw new IllegalStateException("Job completion could not persist its notification delivery",e);}
 }
}
