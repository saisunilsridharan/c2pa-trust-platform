package com.c2pa.portal;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.concurrent.*;
@Component public class CertificateRenewalScheduler {
 private final CertificateRenewalPlanRepository plans;private final CertificateRenewals renewals;private final PrivateCa ca;
 private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(20),Thread.ofVirtual().name("certificate-renewal-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
 public CertificateRenewalScheduler(CertificateRenewalPlanRepository plans,CertificateRenewals renewals,PrivateCa ca){this.plans=plans;this.renewals=renewals;this.ca=ca;}
 private void run(CertificateRenewals.Claim claim){try{WorkspaceContext.call(claim.workspace(),()->{var identity=ca.issue(claim.workspace(),claim.providerVersion(),claim.configuration(),claim.identityId(),claim.fingerprint(),claim.subject());renewals.complete(claim,identity.id());return null;});}catch(Exception e){renewals.failed(claim);}}
 public boolean dispatch(String id,boolean force,Long revision)throws Exception{var claim=renewals.claim(id,force,revision);if(claim.isEmpty())return false;try{executor.execute(()->run(claim.get()));return true;}catch(RejectedExecutionException e){renewals.failed(claim.get());throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Certificate renewal capacity is occupied; retry later");}}
 @Scheduled(fixedDelay=10000) public void due(){for(var p:plans.findByEnabledTrueAndNextCheckAtBeforeOrderByCreatedAtAsc(Instant.now(),org.springframework.data.domain.PageRequest.of(0,20))){if(executor.getQueue().remainingCapacity()==0)break;try{dispatch(p.id,false,null);}catch(Exception ignored){}}}
 @jakarta.annotation.PreDestroy public void close(){executor.shutdown();try{if(!executor.awaitTermination(90,TimeUnit.SECONDS))executor.shutdownNow();}catch(InterruptedException e){executor.shutdownNow();Thread.currentThread().interrupt();}}
}
