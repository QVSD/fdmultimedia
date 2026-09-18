package com.fdmultimedia.api.publishschedules;

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

public interface PublishScheduleRepository extends JpaRepository<PublishSchedule, UUID> {

    Optional<PublishSchedule> findByWorkspaceAndId(Workspace workspace, UUID id);

    /** Used by cancel/reschedule to serialize against a concurrent dispatch. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PublishSchedule s where s.workspace = :workspace and s.id = :id")
    Optional<PublishSchedule> findByWorkspaceAndIdForUpdate(@Param("workspace") Workspace workspace, @Param("id") UUID id);

    /**
     * Native SQL rather than JPQL: PostgreSQL's JDBC driver cannot always
     * determine the type of a parameter used only in a
     * {@code (:param IS NULL OR column OP :param)} guard — it throws
     * "could not determine data type of parameter" for a null Instant/UUID
     * bind with no other type context. Explicit {@code ::} casts resolve it
     * unambiguously; this mirrors {@code JobRepository}'s existing use of
     * native SQL for its own dynamic/optional-filter queries.
     */
    @Query(value = """
            SELECT * FROM publish_schedules
            WHERE workspace_id = :workspaceId
              AND (:status ::text IS NULL OR status = :status ::text)
              AND (:from ::timestamptz IS NULL OR scheduled_for >= :from ::timestamptz)
              AND (:to ::timestamptz IS NULL OR scheduled_for <= :to ::timestamptz)
              AND (:contentDraftId ::uuid IS NULL OR content_draft_id = :contentDraftId ::uuid)
            ORDER BY scheduled_for ASC
            """, nativeQuery = true)
    List<PublishSchedule> search(
            @Param("workspaceId") UUID workspaceId,
            @Param("status") String status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("contentDraftId") UUID contentDraftId);

    /**
     * Claims (locks) exactly one due schedule across all workspaces — this is
     * a background process, not a workspace-scoped request. {@code SKIP
     * LOCKED} lets a second server instance's concurrent poll move straight
     * to the next due row instead of blocking, so two instances can never
     * dispatch the same schedule twice. Mirrors
     * {@code JobRepository.findNextQueuedForUpdate} exactly.
     */
    @Query(value = """
            SELECT * FROM publish_schedules
            WHERE status = 'SCHEDULED' AND scheduled_for <= :now
            ORDER BY scheduled_for ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<PublishSchedule> findNextDueForUpdate(@Param("now") Instant now);
}
