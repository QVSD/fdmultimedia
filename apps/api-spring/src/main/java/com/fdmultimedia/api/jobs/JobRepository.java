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
