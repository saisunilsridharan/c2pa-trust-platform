package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.time.Instant;
public interface AuditAnchorDeliveryRepository extends JpaRepository<AuditAnchorDelivery,String> {
 List<AuditAnchorDelivery> findByWorkspaceIdOrderByCreatedAtDesc(Long workspace,Pageable page);
 List<AuditAnchorDelivery> findByStateAndNextAttemptAtBeforeOrderByCreatedAtAsc(String state,Instant time,Pageable page);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from AuditAnchorDelivery d where d.id=:id") Optional<AuditAnchorDelivery> lockById(@Param("id") String id);
}
