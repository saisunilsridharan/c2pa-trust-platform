package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface ConfigurationVersionRepository extends JpaRepository<ConfigurationVersion,Long> {
    boolean existsByConfigurationRevision(Long revision);
    List<ConfigurationVersion> findAllByOrderByIdDesc(Pageable pageable);
}
