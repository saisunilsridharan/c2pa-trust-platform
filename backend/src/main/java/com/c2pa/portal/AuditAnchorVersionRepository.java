package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.time.Instant;
public interface AuditAnchorVersionRepository extends JpaRepository<AuditAnchorVersion,String> {
 List<AuditAnchorVersion> findByWorkspaceIdOrderByCreatedAtDesc(Long workspace,Pageable page);
}
