package com.c2pa.portal;
public interface PrivateCaVersionRepository extends org.springframework.data.jpa.repository.JpaRepository<PrivateCaVersion,String> { java.util.List<PrivateCaVersion> findByWorkspaceIdOrderByCreatedAtDesc(Long workspace,org.springframework.data.domain.Pageable page); }
