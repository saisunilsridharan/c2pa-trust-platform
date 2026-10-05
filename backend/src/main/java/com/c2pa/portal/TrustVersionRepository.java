package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface TrustVersionRepository extends JpaRepository<TrustVersion,String>{
 List<TrustVersion> findByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId,Pageable page);
}
