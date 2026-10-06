package com.c2pa.portal;
import java.util.*;
import org.springframework.data.domain.Pageable;
public interface PublicTrustVersionRepository extends org.springframework.data.jpa.repository.JpaRepository<PublicTrustVersion,String>{List<PublicTrustVersion> findByWorkspaceIdOrderByCreatedAtDesc(Long workspace,Pageable page);}
