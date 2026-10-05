package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface TimestampVersionRepository extends JpaRepository<TimestampVersion,String>{
 List<TimestampVersion> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId,Pageable page);
}
