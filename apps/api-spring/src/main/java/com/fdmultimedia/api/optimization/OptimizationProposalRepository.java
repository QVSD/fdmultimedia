package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.analytics.CampaignPerformanceReview;
import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.Statistic;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OptimizationProposalRepository extends JpaRepository<OptimizationProposal,UUID>{
    Optional<OptimizationProposal> findByWorkspaceAndId(Workspace workspace,UUID id);
    List<OptimizationProposal> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace,Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from OptimizationProposal p where p.workspace=:workspace and p.id=:id")
    Optional<OptimizationProposal> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace,@Param("id") UUID id);
    @Query("select coalesce(max(p.revision),0) from OptimizationProposal p where p.sourceReview=:review and p.baselinePersonaId=:baseline and p.candidatePersonaId=:candidate and p.metric=:metric and p.statistic=:statistic")
    int maxRevision(@Param("review") CampaignPerformanceReview review,@Param("baseline") UUID baseline,
            @Param("candidate") UUID candidate,@Param("metric") DashboardQuery.Metric metric,@Param("statistic") Statistic statistic);
    Optional<OptimizationProposal> findBySourceReviewAndBaselinePersonaIdAndCandidatePersonaIdAndMetricAndStatisticAndCurrentTrue(
            CampaignPerformanceReview review,UUID baseline,UUID candidate,DashboardQuery.Metric metric,Statistic statistic);
    Optional<OptimizationProposal> findByAutomationOpportunityFingerprint(String fingerprint);
    List<OptimizationProposal> findByWorkspaceAndOriginOrderByCreatedAtDesc(
            Workspace workspace, OptimizationProposalModels.Origin origin, Pageable pageable);
    @Query("select p from OptimizationProposal p where p.workspace=:workspace "
            + "and p.sourceReview.robotRun.robot.id=:robotId and p.baselinePersonaId=:baselinePersonaId "
            + "and p.current=true and p.createdAt>=:epochStart "
            + "and p.status in (com.fdmultimedia.api.optimization.OptimizationProposalModels.Status.READY_FOR_REVIEW,"
            + "com.fdmultimedia.api.optimization.OptimizationProposalModels.Status.APPROVED,"
            + "com.fdmultimedia.api.optimization.OptimizationProposalModels.Status.MATERIALIZED) "
            + "order by p.createdAt desc, p.id desc")
    List<OptimizationProposal> findCurrentForLifecycle(@Param("workspace") Workspace workspace,
            @Param("robotId") UUID robotId, @Param("baselinePersonaId") UUID baselinePersonaId,
            @Param("epochStart") Instant epochStart, Pageable pageable);
    @Query(value="""
            SELECT count(*) FROM optimization_proposals p
            JOIN campaign_performance_reviews r ON r.id=p.source_review_id
            JOIN robot_runs rr ON rr.id=r.robot_run_id
            LEFT JOIN experiments e ON e.id=p.materialized_experiment_id
            WHERE rr.robot_id=:robotId AND p.is_current
              AND (p.status IN ('READY_FOR_REVIEW','APPROVED')
                OR (p.status='MATERIALIZED' AND e.status NOT IN ('COMPLETED','CANCELLED')))
            """,nativeQuery=true)
    long countUnresolvedForRobot(@Param("robotId") UUID robotId);
}
