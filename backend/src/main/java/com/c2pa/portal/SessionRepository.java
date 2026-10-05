package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SessionRepository extends JpaRepository<LoginSession,String>{
 long countByUserIdAndExpiresAtAfter(Long userId,java.time.Instant now);
 void deleteAllByUserId(Long userId);
}
