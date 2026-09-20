package com.fdmultimedia.api.experiments;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.analytics.PublicationDashboardStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Builds the canonical one-row-per-{@link ExperimentAssignment} dataset
 * (item 56/57) via one bounded SQL query. Publication selection is
 * deterministic (item 12/13): among every {@code PUBLISHED} Publication
 * attributed to an assignment (via {@code publication_attributions
 * .experiment_assignment_id} — a direct column, no traversal through
 * ContentDraft/PublishSchedule needed, see {@code PublicationAttributionService}),
 * the earliest {@code published_at}, then the lowest {@code id}, wins — never
 * the Publication with the best metric. Snapshot selection reuses {@link
 * PublicationDashboardStore#snapshotLateralJoinSql} verbatim (item 6): the
 * exact same target-window tie-break Phase 13B/13C/14A already use, just
 * scoped to the one deterministically-selected Publication per assignment
 * instead of to every Publication in a filtered pool.
 */
@Repository
public class ExperimentAnalysisStore {

    /** Item 58: a controlled bound, never a silent truncation — the service checks this before running the row query at all. */
    public static final long MAX_ANALYSIS_ASSIGNMENTS = 10_000;

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM experiment_assignments WHERE experiment_id = :experimentId";

    private final NamedParameterJdbcTemplate jdbc;

    public ExperimentAnalysisStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countAssignments(UUID experimentId) {
        Long count = jdbc.queryForObject(COUNT_SQL, new MapSqlParameterSource("experimentId", experimentId), Long.class);
        return count == null ? 0 : count;
    }

    public List<AssignmentObservationRow> fetch(UUID experimentId, Window window, Metric metric, Instant now) {
        String snapshotJoin = PublicationDashboardStore.snapshotLateralJoinSql(
                window, "sel.publication_id", "sel.published_at", "s." + metric.column() + " AS metric_value", "snap");
        String sql = """
                WITH assignment_publications AS (
                    SELECT ea.id AS assignment_id, ea.experiment_variant_id, rr.status AS robot_run_status,
                           p.id AS publication_id, p.published_at, sa.platform AS provider, a.protocol_deviation,
                           ROW_NUMBER() OVER (
                               PARTITION BY ea.id
                               ORDER BY p.published_at ASC NULLS LAST, p.id ASC
                           ) AS rn
                    FROM experiment_assignments ea
                    JOIN robot_runs rr ON rr.id = ea.robot_run_id
                    LEFT JOIN publication_attributions a ON a.experiment_assignment_id = ea.id
                    LEFT JOIN publications p ON p.id = a.publication_id AND p.status = 'PUBLISHED'
                    LEFT JOIN social_accounts sa ON sa.id = p.social_account_id
                    WHERE ea.experiment_id = :experimentId
                ), selected AS (
                    SELECT assignment_id, experiment_variant_id, robot_run_status, publication_id, published_at,
                           provider, protocol_deviation,
                           (published_at IS NOT NULL AND published_at <= :matureBefore) AS eligible_by_age,
                           (published_at IS NOT NULL AND published_at > :matureBefore) AS too_young
                    FROM assignment_publications
                    WHERE rn = 1
                )
                SELECT sel.assignment_id, sel.experiment_variant_id, sel.robot_run_status, sel.publication_id,
                       sel.published_at, sel.provider, sel.protocol_deviation, sel.eligible_by_age, sel.too_young,
                       snap.id AS snapshot_id, snap.metric_value AS metric_value
                FROM selected sel
                """ + snapshotJoin;

        Instant matureBefore = window == Window.LATEST ? now : now.minusSeconds(window.targetSeconds());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("experimentId", experimentId)
                .addValue("matureBefore", Timestamp.from(matureBefore))
                .addValue("targetAge", window.targetSeconds())
                .addValue("minimumAge", window.minimumSeconds())
                .addValue("maximumAge", window.maximumSeconds());
        return jdbc.query(sql, params, this::mapRow);
    }

    private AssignmentObservationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AssignmentObservationRow(
                rs.getObject("assignment_id", UUID.class),
                rs.getObject("experiment_variant_id", UUID.class),
                rs.getString("robot_run_status"),
                rs.getObject("publication_id", UUID.class),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
                rs.getString("provider"),
                (Boolean) rs.getObject("protocol_deviation"),
                rs.getBoolean("eligible_by_age"),
                rs.getBoolean("too_young"),
                rs.getObject("snapshot_id", UUID.class),
                rs.getBigDecimal("metric_value"));
    }
}
