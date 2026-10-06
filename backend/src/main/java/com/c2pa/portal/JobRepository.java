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
 long countByRemoteWorkerIdAndState(String worker,String state);
 long countByWorkspaceIdAndState(Long workspaceId,String state);
 Optional<SigningJob> findByRequestKey(String requestKey);
 List<SigningJob> findByStateOrderByCreatedAtAsc(String state,Pageable page);
 List<SigningJob> findByStateAndRemoteQueuedFalseOrderByCreatedAtAsc(String state,Pageable page);
 @Query("select j from SigningJob j where j.workspaceId=:workspace and j.state='QUEUED' and j.remoteQueued=true and (j.remoteTarget is null or j.remoteTarget=:worker) order by j.createdAt")
 List<SigningJob> remotePending(@Param("workspace")Long workspace,@Param("worker")String worker,Pageable page);
 List<SigningJob> findByWorkspaceIdAndOwnerOrderByCreatedAtDesc(Long workspaceId,String owner,Pageable page);
 List<SigningJob> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId,Pageable page);
}
