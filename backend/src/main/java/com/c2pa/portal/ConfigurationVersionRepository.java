package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface ConfigurationVersionRepository extends JpaRepository<ConfigurationVersion,Long> {
    boolean existsByWorkspaceIdAndConfigurationRevision(Long workspaceId,Long revision);
    List<ConfigurationVersion> findByWorkspaceIdOrderByIdDesc(Long workspaceId,Pageable pageable);
}
