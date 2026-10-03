package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RobotConfigurationRevisionRepository extends JpaRepository<RobotConfigurationRevision, UUID> {
    List<RobotConfigurationRevision> findByWorkspaceAndRobotIdOrderByRevisionDesc(Workspace workspace, UUID robotId);

    Optional<RobotConfigurationRevision> findByWorkspaceAndRobotIdAndId(Workspace workspace, UUID robotId, UUID id);

    @Query("select coalesce(max(r.revision),0) from RobotConfigurationRevision r where r.robotId=:robotId")
    int maxRevision(@Param("robotId") UUID robotId);

    Optional<RobotConfigurationRevision> findTopByRobotIdOrderByRevisionDesc(UUID robotId);

    Optional<RobotConfigurationRevision> findByRobotIdAndRollbackOfRevisionId(UUID robotId, UUID rollbackOfRevisionId);

    long countByRobotIdAndChangeTypeAndCreatedAtGreaterThanEqual(UUID robotId,
            RobotChangeProposalModels.ChangeType changeType, Instant since);
}
