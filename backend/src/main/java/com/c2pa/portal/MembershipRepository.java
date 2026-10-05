package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface MembershipRepository extends JpaRepository<WorkspaceMembership,Long>{
 Optional<WorkspaceMembership> findByUserIdAndWorkspaceId(Long userId,Long workspaceId);
 List<WorkspaceMembership> findByUserIdOrderByWorkspaceIdAsc(Long userId);
 List<WorkspaceMembership> findByWorkspaceId(Long workspaceId);
 boolean existsByUserId(Long userId);
}
