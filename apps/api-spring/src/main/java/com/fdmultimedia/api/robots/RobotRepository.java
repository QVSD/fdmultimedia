package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RobotRepository extends JpaRepository<Robot, UUID> {

    List<Robot> findByWorkspaceOrderByCreatedAtDesc(Workspace workspace);

    Optional<Robot> findByWorkspaceAndId(Workspace workspace, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Robot r where r.workspace = :workspace and r.id = :id")
    Optional<Robot> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);

    /**
     * Global (not workspace-scoped) due-Robot claim for the central
     * scheduler — mirrors {@code PublishScheduleRepository.findNextDueForUpdate}
     * and {@code JobRepository}'s own claim queries exactly: {@code FOR
     * UPDATE SKIP LOCKED} so two API instances polling concurrently can
     * never start two runs for the same due Robot.
     */
    @Query(value = """
            SELECT * FROM robots
            WHERE status = 'ACTIVE' AND cadence_type = 'INTERVAL' AND next_run_at <= :now
            ORDER BY next_run_at ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Robot> findNextDueForUpdate(@Param("now") Instant now);
}
