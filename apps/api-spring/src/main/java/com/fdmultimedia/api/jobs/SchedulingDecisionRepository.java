package com.fdmultimedia.api.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface SchedulingDecisionRepository extends JpaRepository<SchedulingDecision, UUID> {

    @Query("""
            select new com.fdmultimedia.api.jobs.SchedulingDecisionSummary(
                d.createdAt, d.job.id, d.jobType, d.worker.id, d.worker.name, d.policy,
                d.suitabilityScore, d.telemetryFresh, d.fallbackUsed, d.starvationOverride,
                d.reasonCodes, d.activeJobs, d.maxActiveJobs, d.attempt)
            from SchedulingDecision d
            where d.workspace.id = :workspaceId and d.createdAt >= :since
            order by d.createdAt desc
            """)
    List<SchedulingDecisionSummary> recent(
            @Param("workspaceId") UUID workspaceId,
            @Param("since") Instant since,
            Pageable pageable);

    @Query(value = """
            SELECT COUNT(*) AS claims,
                   COUNT(*) FILTER (WHERE fallback_used) AS "fallbackClaims",
                   COUNT(*) FILTER (WHERE starvation_override) AS "starvationOverrideClaims"
            FROM scheduling_decisions
            WHERE workspace_id = :workspaceId AND created_at >= :since
            """, nativeQuery = true)
    SchedulingCountsView counts(@Param("workspaceId") UUID workspaceId, @Param("since") Instant since);

    @Modifying
    @Query(value = """
            DELETE FROM scheduling_decisions
            WHERE id IN (
                SELECT id FROM scheduling_decisions
                WHERE created_at < :threshold
                ORDER BY created_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteBatchBefore(@Param("threshold") Instant threshold, @Param("batchSize") int batchSize);
}
