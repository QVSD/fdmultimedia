package com.fdmultimedia.api.campaigns;

import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.workspaces.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignCopySetRepository extends JpaRepository<CampaignCopySet, UUID> {

    Optional<CampaignCopySet> findByWorkspaceAndId(Workspace workspace, UUID id);

    List<CampaignCopySet> findByRobotRunOrderByRevisionDesc(RobotRun robotRun);

    Optional<CampaignCopySet> findByRobotRunAndCurrentTrue(RobotRun robotRun);

    Optional<CampaignCopySet> findByGenerationJobId(UUID generationJobId);
}
