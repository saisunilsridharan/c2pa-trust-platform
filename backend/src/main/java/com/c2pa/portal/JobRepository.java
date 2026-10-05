package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface JobRepository extends JpaRepository<SigningJob,String> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select j from SigningJob j where j.id=:id") Optional<SigningJob> lockById(@Param("id") String id);
 long countByState(String state);
 long countByWorkspaceIdAndState(Long workspaceId,String state);
 Optional<SigningJob> findByRequestKey(String requestKey);
 List<SigningJob> findByStateOrderByCreatedAtAsc(String state,Pageable page);
 List<SigningJob> findByWorkspaceIdAndOwnerOrderByCreatedAtDesc(Long workspaceId,String owner,Pageable page);
 List<SigningJob> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId,Pageable page);
}
