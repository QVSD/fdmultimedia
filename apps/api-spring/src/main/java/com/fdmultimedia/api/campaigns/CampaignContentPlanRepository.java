package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignContentPlanRepository extends JpaRepository<CampaignContentPlan, UUID> {

    Optional<CampaignContentPlan> findByWorkspaceAndId(Workspace workspace, UUID id);

    List<CampaignContentPlan> findByRobotRunOrderByRevisionDesc(RobotRun robotRun);

    Optional<CampaignContentPlan> findByRobotRunAndCurrentTrue(RobotRun robotRun);

    Optional<CampaignContentPlan> findByGenerationJobId(UUID generationJobId);
}
