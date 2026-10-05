package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
@Service public class WebhookDispatcher {
 private final WebhookDeliveryRepository deliveries;private final WebhookService service;private final AuditService audit;
 public WebhookDispatcher(WebhookDeliveryRepository deliveries,WebhookService service,AuditService audit){this.deliveries=deliveries;this.service=service;this.audit=audit;}
 @Transactional public void dispatch(String id){
  var record=deliveries.lockById(id).orElseThrow();if(!record.state.equals("PENDING") || record.nextAttemptAt.isAfter(Instant.now()))return;
  record.attempts++;record.statusCode=null;
  int maximum=1;try{var c=service.decode(record.configuration);maximum=c.maxAttempts();record.statusCode=service.send(record.workspaceId,record.id,c,record.payload);}catch(Exception ignored){}
  if(record.statusCode!=null && record.statusCode>=200 && record.statusCode<300)record.state="DELIVERED";
  else if(record.attempts>=maximum)record.state="FAILED";
  else record.nextAttemptAt=Instant.now().plusSeconds(Math.min(3600,5L << Math.min(record.attempts,10)));
  deliveries.saveAndFlush(record);audit.record(record.workspaceId,"WEBHOOK_"+record.state,record.id);
 }
}
