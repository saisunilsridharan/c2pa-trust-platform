package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface CredentialRepository extends JpaRepository<EncryptedCredential,String>{
 List<EncryptedCredential> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId,Pageable page);
 @Query("select distinct c.keyFingerprint from EncryptedCredential c") List<String> fingerprints();
}
