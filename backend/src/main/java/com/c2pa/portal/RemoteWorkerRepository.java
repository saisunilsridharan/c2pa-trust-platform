package com.c2pa.portal;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
public interface RemoteWorkerRepository extends JpaRepository<RemoteWorker,String> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select w from RemoteWorker w where w.id=:id") Optional<RemoteWorker> lockById(@Param("id")String id);
 List<RemoteWorker> findByWorkspaceIdOrderByCreatedAtDesc(Long workspace,Pageable page);
 boolean existsByWorkspaceIdAndEnabledTrueAndExpiresAtAfter(Long workspace,java.time.Instant now);
 long countByWorkspaceId(Long workspace);
}
