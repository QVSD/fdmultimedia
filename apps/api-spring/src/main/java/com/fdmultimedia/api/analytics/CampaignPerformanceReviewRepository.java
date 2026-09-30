package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.robots.RobotRun;
import com.fdmultimedia.api.workspaces.Workspace;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface CampaignPerformanceReviewRepository extends JpaRepository<CampaignPerformanceReview,UUID> {
    Optional<CampaignPerformanceReview> findByWorkspaceAndId(Workspace workspace, UUID id);
    List<CampaignPerformanceReview> findByRobotRunOrderByCreatedAtDesc(RobotRun run);
    List<CampaignPerformanceReview> findByRobotRunOrderByCreatedAtDesc(RobotRun run, Pageable pageable);
    @Query("select coalesce(max(r.revision),0) from CampaignPerformanceReview r where r.robotRun=:run and r.observationWindow=:window")
    int maxRevision(@Param("run") RobotRun run, @Param("window") DashboardQuery.Window window);
}
