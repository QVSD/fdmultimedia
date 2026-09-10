package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByWorkspaceOrderByQueuedAtDesc(Workspace workspace);

    Optional<Job> findByWorkspaceAndId(Workspace workspace, UUID id);

    @Query(
            value = """
                    SELECT *
                    FROM jobs
                    WHERE workspace_id = :workspaceId
                      AND status = 'QUEUED'
                      AND type IN (:types)
                    ORDER BY queued_at ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    Optional<Job> findNextQueuedForUpdate(
            @Param("workspaceId") UUID workspaceId,
            @Param("types") List<String> types);

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
