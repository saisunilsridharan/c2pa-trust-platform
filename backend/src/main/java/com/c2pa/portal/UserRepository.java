package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface UserRepository extends JpaRepository<PortalUser,Long> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select u from PortalUser u where u.id = :id")
 Optional<PortalUser> lockById(@org.springframework.data.repository.query.Param("id") Long id);
 Optional<PortalUser> findByUsername(String username);
 long countByRoleAndEnabledTrue(String role);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select u from PortalUser u where u.username = :username")
 Optional<PortalUser> lockByUsername(@org.springframework.data.repository.query.Param("username") String username);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select u from PortalUser u where u.role = 'ADMIN' and u.enabled = true")
 List<PortalUser> lockAdministrators();
}
