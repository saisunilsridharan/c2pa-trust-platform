package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface RemoteWorkerSettingsRepository extends JpaRepository<RemoteWorkerSettings,Long>{
 List<RemoteWorkerSettings> findByAgentEnabledTrue(org.springframework.data.domain.Pageable page);
}
