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
    @Query("select r from CampaignPerformanceReview r where r.workspace=:workspace and r.robotRun.robot.id=:robotId "
            + "and r.evidenceStatus=com.fdmultimedia.api.analytics.CampaignPerformanceModels.EvidenceStatus.READY "
            + "and r.observationWindow=com.fdmultimedia.api.analytics.DashboardQuery.Window.H72 "
            + "and r.primaryMetric=com.fdmultimedia.api.analytics.DashboardQuery.Metric.TOTAL_INTERACTIONS "
            + "order by r.createdAt desc,r.id desc")
    List<CampaignPerformanceReview> findCanonicalReadyForRobot(@Param("workspace") Workspace workspace,
            @Param("robotId") UUID robotId,Pageable pageable);
}
