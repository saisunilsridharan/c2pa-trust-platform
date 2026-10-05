package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
public interface AuthenticationRateBucketRepository extends JpaRepository<AuthenticationRateBucket,String>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select b from AuthenticationRateBucket b where b.id='GLOBAL'") Optional<AuthenticationRateBucket> lockGlobal();
 @Modifying @Query("delete from AuthenticationRateBucket b where b.id<>'GLOBAL' and b.startedAt<:cutoff") int removeOld(@Param("cutoff") Instant cutoff);
}
