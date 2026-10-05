package com.c2pa.portal;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SessionRepository extends JpaRepository<LoginSession,String>{
 void deleteAllByUserId(Long userId);
}
