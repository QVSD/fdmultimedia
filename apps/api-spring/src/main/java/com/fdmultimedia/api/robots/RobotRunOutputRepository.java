package com.fdmultimedia.api.robots;
import com.fdmultimedia.api.workspaces.Workspace;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface RobotRunOutputRepository extends JpaRepository<RobotRunOutput,UUID>{
 List<RobotRunOutput> findByRobotRunOrderBySelectionOrderAsc(RobotRun run);
 Optional<RobotRunOutput> findByWorkspaceAndId(Workspace workspace,UUID id);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @Query("select o from RobotRunOutput o where o.id=:id") Optional<RobotRunOutput> findByIdForUpdate(@Param("id") UUID id);
}
