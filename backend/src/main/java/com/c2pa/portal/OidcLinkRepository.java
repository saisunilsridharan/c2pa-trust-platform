package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface OidcLinkRepository extends JpaRepository<OidcLink,Long>{Optional<OidcLink> findByIssuerAndSubject(String issuer,String subject);}
