package com.c2pa.portal;
public interface RevocationVersionRepository extends org.springframework.data.jpa.repository.JpaRepository<RevocationVersion,String>{java.util.List<RevocationVersion> findByWorkspaceIdOrderByCreatedAtDesc(Long workspace,org.springframework.data.domain.Pageable page);}
