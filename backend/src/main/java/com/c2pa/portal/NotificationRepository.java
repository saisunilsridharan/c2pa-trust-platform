package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface NotificationRepository extends JpaRepository<JobNotification,String> {
 boolean existsByEventKey(String key);
 List<JobNotification> findByWorkspaceIdAndOwnerOrderByCreatedAtDesc(Long workspace,String owner,Pageable page);
 long countByWorkspaceIdAndOwnerAndReadAtIsNull(Long workspace,String owner);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select n from JobNotification n where n.id=:id") Optional<JobNotification> lockById(@Param("id") String id);
}
