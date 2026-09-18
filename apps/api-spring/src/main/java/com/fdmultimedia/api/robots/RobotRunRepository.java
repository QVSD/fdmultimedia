package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RobotRunRepository extends JpaRepository<RobotRun, UUID> {

    List<RobotRun> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    List<RobotRun> findByRobotOrderByCreatedAtDesc(Robot robot);

    Optional<RobotRun> findByWorkspaceAndId(Workspace workspace, UUID id);

    /**
     * Used by both the reconciliation poller and any direct reconcile-on-read
     * call. {@code SKIP LOCKED} on a single-row fetch: if another
     * transaction is already reconciling this exact run, this call simply
     * returns empty rather than blocking — safe because the next poll cycle
     * (or the next read) tries again, and reconciliation is idempotent.
     */
    @Query(value = "SELECT * FROM robot_runs WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<RobotRun> findByIdForUpdateSkipLocked(@Param("id") UUID id);

    boolean existsByRobotAndStatusNotIn(Robot robot, List<RobotRunStatus> terminalStatuses);

    boolean existsByRobotAndSourceAssetAndStatus(Robot robot, MediaAsset sourceAsset, RobotRunStatus status);

    long countByRobotAndCreatedAtGreaterThanEqual(Robot robot, Instant since);

    long countByWorkspaceAndStatusNotIn(Workspace workspace, List<RobotRunStatus> terminalStatuses);

    /** Bounded batch for the reconciliation poller — never scans terminal history. */
    @Query(value = """
            SELECT id FROM robot_runs
            WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
            ORDER BY created_at ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findNonTerminalIds(@Param("limit") int limit);
}
