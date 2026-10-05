package com.c2pa.portal;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
@Component public class AuditAnchorScheduler {
 private final AuditAnchorSettingsRepository settings;private final AuditAnchorDeliveryRepository deliveries;private final AuditAnchors anchors;
 public AuditAnchorScheduler(AuditAnchorSettingsRepository settings,AuditAnchorDeliveryRepository deliveries,AuditAnchors anchors){this.settings=settings;this.deliveries=deliveries;this.anchors=anchors;}
 @Scheduled(fixedDelay=60000) public void checkpoint(){for(var s:settings.findByNextCheckpointAtBeforeOrderByIdAsc(Instant.now(),PageRequest.of(0,20)))try{anchors.queue(s.id,true);}catch(Exception ignored){}}
 @Scheduled(fixedDelay=5000) public void dispatch(){for(var d:deliveries.findByStateAndNextAttemptAtBeforeOrderByCreatedAtAsc("PENDING",Instant.now(),PageRequest.of(0,10)))try{anchors.dispatch(d.id);}catch(Exception ignored){}}
}
