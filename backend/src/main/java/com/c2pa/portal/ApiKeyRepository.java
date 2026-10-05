package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface ApiKeyRepository extends JpaRepository<PortalApiKey,String> {
 Optional<PortalApiKey> findByTokenHash(String hash);
 List<PortalApiKey> findByWorkspaceIdAndUserIdOrderByCreatedAtDesc(Long workspaceId,Long userId,Pageable page);
 void deleteAllByUserId(Long userId);
}
