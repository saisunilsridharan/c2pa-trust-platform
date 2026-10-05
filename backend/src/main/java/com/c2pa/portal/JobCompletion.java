package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
@Service
public class JobCompletion {
 private final JobRepository jobs;private final AuditRepository audit;private final NotificationRepository notifications;
 private final WebhookService webhooks;
 public JobCompletion(JobRepository jobs,AuditRepository audit,NotificationRepository notifications,WebhookService webhooks){this.jobs=jobs;this.audit=audit;this.notifications=notifications;this.webhooks=webhooks;}
 @Transactional public void finish(SigningJob job){
  if(!java.util.Set.of("COMPLETED","FAILED").contains(job.state))throw new IllegalArgumentException();jobs.saveAndFlush(job);
  String eventKey=job.id+":"+job.attempts;
  if(notifications.existsByEventKey(eventKey))return;
  JobNotification notification=new JobNotification();notification.id=UUID.randomUUID().toString();notification.eventKey=eventKey;notification.workspaceId=job.workspaceId;notification.owner=job.owner;notification.jobId=job.id;notification.outcome=job.state;notification.createdAt=Instant.now();notifications.saveAndFlush(notification);
  AuditEvent event=new AuditEvent();event.createdAt=notification.createdAt;event.actor=job.owner;event.workspaceId=job.workspaceId;event.action="SIGNING_JOB_"+job.state;event.reference=job.id;audit.save(event);
  try{webhooks.enqueue(job);}catch(Exception e){throw new IllegalStateException("Job completion could not persist its notification delivery",e);}
 }
}
