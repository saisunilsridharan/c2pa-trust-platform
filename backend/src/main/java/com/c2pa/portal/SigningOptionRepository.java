package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface SigningOptionRepository extends JpaRepository<SigningOption,String> {
 List<SigningOption> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId,Pageable page);
 List<SigningOption> findByWorkspaceIdAndEnabledTrueOrderByCreatedAtDesc(Long workspaceId,Pageable page);
}
