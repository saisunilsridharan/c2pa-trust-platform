package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface OidcTransactionRepository extends JpaRepository<OidcTransaction,String>{
 java.util.List<OidcTransaction> findByExpiresAtBefore(java.time.Instant before,org.springframework.data.domain.Pageable page);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from OidcTransaction t where t.id=:id") Optional<OidcTransaction> lockById(@Param("id") String id);
}
