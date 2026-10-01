package com.fdmultimedia.api.optimization;

import com.fdmultimedia.api.analytics.CampaignPerformanceReview;
import com.fdmultimedia.api.analytics.DashboardQuery;
import com.fdmultimedia.api.optimization.OptimizationProposalModels.Statistic;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
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
}
