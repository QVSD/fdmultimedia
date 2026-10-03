package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RobotAdaptivePolicyRevisionRepository extends JpaRepository<RobotAdaptivePolicyRevision,UUID>{
    List<RobotAdaptivePolicyRevision> findByWorkspaceAndRobotIdOrderByRevisionDesc(Workspace workspace,UUID robotId,Pageable pageable);
}
