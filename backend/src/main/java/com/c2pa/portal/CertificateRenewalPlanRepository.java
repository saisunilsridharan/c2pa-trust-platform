package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.time.Instant;
public interface CertificateRenewalPlanRepository extends JpaRepository<CertificateRenewalPlan,String>{
 List<CertificateRenewalPlan> findByWorkspaceIdOrderByCreatedAtDesc(Long workspace,Pageable page);
 List<CertificateRenewalPlan> findByEnabledTrueAndNextCheckAtBeforeOrderByCreatedAtAsc(Instant time,Pageable page);
 boolean existsByWorkspaceIdAndCurrentChoiceIdAndEnabledTrueAndIdNot(Long workspace,String choice,String id);
 boolean existsByWorkspaceIdAndCurrentChoiceIdAndEnabledTrue(Long workspace,String choice);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from CertificateRenewalPlan p where p.id=:id") Optional<CertificateRenewalPlan> lockById(@Param("id") String id);
}
