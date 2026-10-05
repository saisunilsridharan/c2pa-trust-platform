package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.time.Instant;
public interface AuditAnchorSettingsRepository extends JpaRepository<AuditAnchorSettings,Long> {
 List<AuditAnchorSettings> findByNextCheckpointAtBeforeOrderByIdAsc(Instant time,Pageable page);
}
