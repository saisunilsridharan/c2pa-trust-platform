package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface HardwareIdentityRepository extends JpaRepository<HardwareIdentity,String>{
 List<HardwareIdentity> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId,Pageable page);
}
