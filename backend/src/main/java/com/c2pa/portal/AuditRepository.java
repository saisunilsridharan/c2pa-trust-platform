package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface AuditRepository extends JpaRepository<AuditEvent,Long> {
    List<AuditEvent> findByWorkspaceIdOrderByIdDesc(Long workspaceId,Pageable pageable);
    List<AuditEvent> findByWorkspaceIdOrderByIdAsc(Long workspaceId,Pageable pageable);
    java.util.Optional<AuditEvent> findByWorkspaceIdAndChainIndex(Long workspaceId,Long index);
}
