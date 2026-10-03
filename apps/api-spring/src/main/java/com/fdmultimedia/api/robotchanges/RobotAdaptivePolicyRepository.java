package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RobotAdaptivePolicyRepository extends JpaRepository<RobotAdaptivePolicy,UUID>{
    Optional<RobotAdaptivePolicy> findByWorkspaceAndRobotId(Workspace workspace,UUID robotId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RobotAdaptivePolicy p where p.workspace=:workspace and p.robotId=:robotId")
    Optional<RobotAdaptivePolicy> findForUpdate(@Param("workspace") Workspace workspace,@Param("robotId") UUID robotId);
}
