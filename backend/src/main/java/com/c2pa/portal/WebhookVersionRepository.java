package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface WebhookVersionRepository extends JpaRepository<WebhookVersion,String>{List<WebhookVersion> findByWorkspaceIdOrderByCreatedAtDesc(Long workspace,Pageable page);}
