package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.time.Instant;
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery,String>{
 List<WebhookDelivery> findByWorkspaceIdOrderByCreatedAtDesc(Long workspace,Pageable page);
 List<WebhookDelivery> findByStateAndNextAttemptAtBeforeOrderByCreatedAtAsc(String state,Instant time,Pageable page);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from WebhookDelivery d where d.id=:id") Optional<WebhookDelivery> lockById(@Param("id") String id);
}
