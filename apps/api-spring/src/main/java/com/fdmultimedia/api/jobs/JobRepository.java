package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, UUID> {

    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE status = 'QUEUED') AS queued,
                   COUNT(*) FILTER (WHERE status = 'ASSIGNED') AS assigned,
                   COUNT(*) FILTER (WHERE status = 'RUNNING') AS running,
                   COUNT(*) FILTER (WHERE status = 'SUCCEEDED' AND finished_at >= :since) AS succeeded,
                   COUNT(*) FILTER (WHERE status = 'FAILED' AND finished_at >= :since) AS failed,
                   MIN(queued_at) FILTER (WHERE status = 'QUEUED') AS "oldestQueuedAt"
            FROM jobs WHERE workspace_id = :workspaceId
            """, nativeQuery = true)
    QueueSnapshotView schedulingQueueSnapshot(@Param("workspaceId") UUID workspaceId, @Param("since") Instant since);

    List<Job> findByWorkspaceOrderByQueuedAtDesc(Workspace workspace);

    Optional<Job> findByWorkspaceAndId(Workspace workspace, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from Job job where job.workspace = :workspace and job.id = :id")
    Optional<Job> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);

    @Query(
            value = """
                    SELECT *
                    FROM jobs
                    WHERE workspace_id = :workspaceId
                      AND status = 'QUEUED'
                      AND type IN (:types)
                      AND (
                        type <> 'ANALYZE_HIGHLIGHTS'
                        OR COALESCE(payload ->> 'analyzerType', 'DETERMINISTIC_V1') IN (:highlightAnalyzers)
                      )
                    ORDER BY queued_at ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    Optional<Job> findNextQueuedForUpdate(
            @Param("workspaceId") UUID workspaceId,
            @Param("types") List<String> types,
            @Param("highlightAnalyzers") List<String> highlightAnalyzers);

    @Query(
            value = """
                    SELECT *
                    FROM jobs
                    WHERE workspace_id = :workspaceId
                      AND status = 'QUEUED'
                      AND type IN (:types)
                      AND (
                        type <> 'ANALYZE_HIGHLIGHTS'
                        OR COALESCE(payload ->> 'analyzerType', 'DETERMINISTIC_V1') IN (:highlightAnalyzers)
                      )
                    ORDER BY queued_at ASC, id ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<Job> findQueuedCandidatesForUpdate(
            @Param("workspaceId") UUID workspaceId,
            @Param("types") List<String> types,
            @Param("highlightAnalyzers") List<String> highlightAnalyzers,
            @Param("limit") int limit);

    default Optional<Job> findNextQueuedForUpdate(UUID workspaceId, List<String> types) {
        return findNextQueuedForUpdate(workspaceId, types, List.of("DETERMINISTIC_V1"));
    }

    @Query(
            value = """
                    SELECT *
                    FROM jobs
                    WHERE workspace_id = :workspaceId
                      AND status IN ('ASSIGNED', 'RUNNING')
                      AND lease_expires_at <= :now
                    ORDER BY lease_expires_at ASC
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<Job> findExpiredLeasesForUpdate(@Param("workspaceId") UUID workspaceId, @Param("now") Instant now);
}
