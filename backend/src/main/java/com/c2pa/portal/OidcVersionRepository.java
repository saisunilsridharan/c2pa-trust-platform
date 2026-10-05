package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface OidcVersionRepository extends JpaRepository<OidcVersion,String>{List<OidcVersion> findAllByOrderByCreatedAtDesc(Pageable page);}
