package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface JobRepository extends JpaRepository<SigningJob,String> {
 long countByState(String state);
 long countByWorkspaceIdAndState(Long workspaceId,String state);
 Optional<SigningJob> findByRequestKey(String requestKey);
 List<SigningJob> findByStateOrderByCreatedAtAsc(String state,Pageable page);
 List<SigningJob> findByWorkspaceIdAndOwnerOrderByCreatedAtDesc(Long workspaceId,String owner,Pageable page);
 List<SigningJob> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId,Pageable page);
}
