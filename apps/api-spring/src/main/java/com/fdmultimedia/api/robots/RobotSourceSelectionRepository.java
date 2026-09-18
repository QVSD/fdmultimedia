package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.assets.MediaAsset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The one query at the heart of dynamic content selection — everything
 * eligibility- and ordering-related happens here in PostgreSQL, never by
 * loading a source's membership into Java and filtering there. A
 * fragment-only repository (no CRUD surface) since {@link Robot} isn't the
 * queried entity.
 */
public interface RobotSourceSelectionRepository extends Repository<Robot, UUID> {

    /**
     * OLDEST_UNPROCESSED: the membership row with the smallest
     * {@code added_at} (the asset that has been sitting in this source
     * longest), tie-broken by membership id for a fully deterministic order.
     * Eligibility mirrors {@code RobotService.validateSourceEligibility}
     * exactly (READY, INSPECTED, has video, known positive duration),
     * requires the asset still be an ORIGINAL (defense in depth alongside
     * the membership-insert-time check), and excludes any asset this exact
     * Robot has ever selected before regardless of that earlier run's
     * outcome — a failed run still permanently consumes the asset for this
     * Robot, so a broken source item is never retried forever.
     * {@code FOR UPDATE SKIP LOCKED} mirrors the exact idiom already used
     * for Job/PublishSchedule/Robot claiming.
     */
    @Query(value = """
            SELECT ma.id FROM media_assets ma
            JOIN content_source_assets csa ON csa.media_asset_id = ma.id
            WHERE csa.content_source_id = :contentSourceId
              AND ma.workspace_id = :workspaceId
              AND ma.derivation_type = 'ORIGINAL'
              AND ma.status = 'READY'
              AND ma.inspection_status = 'INSPECTED'
              AND ma.has_video = true
              AND ma.duration_ms IS NOT NULL AND ma.duration_ms > 0
              AND NOT EXISTS (
                  SELECT 1 FROM robot_runs rr
                  WHERE rr.robot_id = :robotId AND rr.source_asset_id = ma.id
              )
            ORDER BY csa.added_at ASC, csa.id ASC
            LIMIT 1
            FOR UPDATE OF ma SKIP LOCKED
            """, nativeQuery = true)
    Optional<UUID> findOldestUnprocessedAssetId(
            @Param("contentSourceId") UUID contentSourceId,
            @Param("workspaceId") UUID workspaceId,
            @Param("robotId") UUID robotId);

    /** NEWEST_UNPROCESSED: same eligibility/exclusion, ordered by most recently added instead. */
    @Query(value = """
            SELECT ma.id FROM media_assets ma
            JOIN content_source_assets csa ON csa.media_asset_id = ma.id
            WHERE csa.content_source_id = :contentSourceId
              AND ma.workspace_id = :workspaceId
              AND ma.derivation_type = 'ORIGINAL'
              AND ma.status = 'READY'
              AND ma.inspection_status = 'INSPECTED'
              AND ma.has_video = true
              AND ma.duration_ms IS NOT NULL AND ma.duration_ms > 0
              AND NOT EXISTS (
                  SELECT 1 FROM robot_runs rr
                  WHERE rr.robot_id = :robotId AND rr.source_asset_id = ma.id
              )
            ORDER BY csa.added_at DESC, csa.id ASC
            LIMIT 1
            FOR UPDATE OF ma SKIP LOCKED
            """, nativeQuery = true)
    Optional<UUID> findNewestUnprocessedAssetId(
            @Param("contentSourceId") UUID contentSourceId,
            @Param("workspaceId") UUID workspaceId,
            @Param("robotId") UUID robotId);
}
