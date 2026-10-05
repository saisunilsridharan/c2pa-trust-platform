package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface StorageVersionRepository extends JpaRepository<StorageVersion,String> {
 List<StorageVersion> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId,Pageable page);
}
