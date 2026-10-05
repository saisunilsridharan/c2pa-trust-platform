package com.c2pa.portal;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface WorkspaceRepository extends JpaRepository<Workspace,Long>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select w from Workspace w where w.id=:id") Optional<Workspace> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
