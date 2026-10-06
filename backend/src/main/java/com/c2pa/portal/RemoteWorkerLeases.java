package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.*;
@Service public class RemoteWorkerLeases {
 private final RemoteWorkerRepository workers;private final JobRepository jobs;private final JobCompletion completion;
 public RemoteWorkerLeases(RemoteWorkerRepository workers,JobRepository jobs,JobCompletion completion){this.workers=workers;this.jobs=jobs;this.completion=completion;}
 RemoteWorker worker(String id){var w=workers.lockById(id).filter(row->row.expiresAt.isAfter(Instant.now())).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();if(attributes!=null && attributes.getAttribute("portal.workerTokenHash",org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST)!=null && !Objects.equals(attributes.getAttribute("portal.workerTokenHash",org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST),w.tokenHash))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return w;}
 private boolean allowed(RemoteWorker w,SigningJob j){return j.workspaceId.equals(w.workspaceId) && j.remoteQueued && (j.remoteTarget==null?w.enabled && w.testedAt!=null:Objects.equals(j.remoteTarget,w.id) && Objects.equals(w.probeJobId,j.id));}
 @Transactional public Map<String,Object> heartbeat(String id,boolean namespace){var w=worker(id);w.namespaceAvailable=namespace;if(w.lastSeenAt==null || w.lastSeenAt.isBefore(Instant.now().minusSeconds(30)))w.lastSeenAt=Instant.now();workers.saveAndFlush(w);return Map.of("workerId",w.id,"protocol",1,"enabled",w.enabled);}
 @Transactional public Optional<SigningJob> claim(String id){
  var w=worker(id);if(w.lastSeenAt==null || w.lastSeenAt.isBefore(Instant.now().minusSeconds(120)) || jobs.countByRemoteWorkerIdAndState(id,"RUNNING")>=1)return Optional.empty();
  for(var candidate:jobs.remotePending(w.workspaceId,id,PageRequest.of(0,100))){var j=jobs.lockById(candidate.id).orElseThrow();if(!j.state.equals("QUEUED") || !allowed(w,j) || "NAMESPACE".equals(j.sandboxMode) && !w.namespaceAvailable)continue;
   if(!j.keyPath.startsWith("pkcs11:"))throw new IllegalStateException("Remote signing requires an HSM");
   j.state="RUNNING";j.attempts++;j.leaseToken=UUID.randomUUID().toString();j.leaseUntil=Instant.now().plusSeconds(600);j.remoteWorkerId=w.id;j.remoteCallbacks=0;j.resultAttempt=j.leaseToken;j.attemptDirectories=j.attemptDirectories==null?j.leaseToken:j.attemptDirectories+","+j.leaseToken;return Optional.of(jobs.saveAndFlush(j));
  }return Optional.empty();
 }
 private SigningJob current(RemoteWorker w,String job,String lease){var j=jobs.lockById(job).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  if(!allowed(w,j) || !Objects.equals(j.remoteWorkerId,w.id) || !j.state.equals("RUNNING") || lease==null || !Objects.equals(j.leaseToken,lease) || j.leaseUntil==null || !j.leaseUntil.isAfter(Instant.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Worker lease is unavailable");return j;
 }
 @Transactional public SigningJob access(String id,String job,String lease){return current(worker(id),job,lease);}
 @Transactional public SigningJob callback(String id,String job,String lease){var j=current(worker(id),job,lease);if(j.remoteCallbacks>=4)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Callback budget exhausted");j.remoteCallbacks++;return jobs.saveAndFlush(j);}
 @Transactional public void prepareProbe(RemoteWorker w){if(w.probeJobId!=null){var current=jobs.lockById(w.probeJobId);if(current.isPresent() && Set.of("QUEUED","RUNNING").contains(current.get().state))throw new ResponseStatusException(HttpStatus.CONFLICT,"A readiness probe is already pending");}}
 @Transactional public void withdrawProbe(RemoteWorker w){if(w.probeJobId==null)return;var found=jobs.lockById(w.probeJobId);if(found.isEmpty())return;var j=found.get();if(!Objects.equals(j.remoteTarget,w.id) || !j.workspaceId.equals(w.workspaceId) || !Set.of("QUEUED","RUNNING").contains(j.state))return;j.leaseToken=null;j.leaseUntil=null;j.state="FAILED";j.error="Remote readiness probe was withdrawn.";j.completedAt=Instant.now();completion.finish(j);}
 @FunctionalInterface public interface Publication {void publish()throws Exception;}
 @Transactional(rollbackFor=Exception.class) public void finish(String id,SigningJob result,Publication publication)throws Exception{var w=worker(id);var j=current(w,result.id,result.leaseToken);publication.publish();result.revision=j.revision;result.completedAt=Instant.now();completion.finish(result);if(result.state.equals("COMPLETED") && Objects.equals(w.probeJobId,result.id)){w.testedAt=Instant.now();workers.saveAndFlush(w);}}
}
