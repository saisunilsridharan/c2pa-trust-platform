package com.c2pa.portal;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
@Component public class WebhookScheduler {
 private final WebhookDeliveryRepository records;private final WebhookDispatcher dispatcher;
 public WebhookScheduler(WebhookDeliveryRepository records,WebhookDispatcher dispatcher){this.records=records;this.dispatcher=dispatcher;}
 @Scheduled(cron="${portal.webhooks.schedule:*/5 * * * * *}") public void deliver(){for(var record:records.findByStateAndNextAttemptAtBeforeOrderByCreatedAtAsc("PENDING",Instant.now(),org.springframework.data.domain.PageRequest.of(0,10)))dispatcher.dispatch(record.id);}
}
