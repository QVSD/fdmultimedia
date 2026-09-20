package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.DashboardModels.*;
import com.fdmultimedia.api.analytics.DashboardQuery.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PublicationDashboardStore {
    private static final int MAX_GROUPS = 100;
    private static final String[] METRICS = {"views", "reach", "likes", "comments", "shares", "saves", "total_interactions"};
    private final NamedParameterJdbcTemplate jdbc;

    public PublicationDashboardStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary(DashboardQuery query, Instant now) {
        String sql = observed(query) + "SELECT " + coverageSql() + ", " + metricSql() + " FROM observed";
        return jdbc.queryForObject(sql, params(query, now), (rs, row) ->
                new Summary(DashboardModels.filters(query), coverage(rs), metrics(rs)));
    }

    public Trend trend(DashboardQuery query, Metric metric, Instant now) {
        String sql = observed(query) + "SELECT (published_at AT TIME ZONE 'UTC')::date AS cohort_date, "
                + coverageSql() + ", " + aggregate(metric.column()) + " FROM observed "
                + "GROUP BY cohort_date ORDER BY cohort_date";
        List<TrendPoint> points = jdbc.query(sql, params(query, now), (rs, row) ->
                new TrendPoint(rs.getObject("cohort_date", LocalDate.class), coverage(rs), metric(rs, metric)));
        return new Trend(DashboardModels.filters(query), metric, points);
    }

    public Breakdown breakdown(DashboardQuery query, Dimension dimension, Instant now) {
        String key = dimensionKeyExpr(dimension);
        String label = dimensionLabelExpr(dimension);
        String sql = observed(query) + "SELECT " + key + " AS dimension_key, " + label + " AS dimension_label, "
                + coverageSql() + ", " + metricSql() + " FROM observed "
                + "GROUP BY " + key + " ORDER BY dimension_label, dimension_key LIMIT " + (MAX_GROUPS + 1);
        List<BreakdownRow> rows = jdbc.query(sql, params(query, now), (rs, row) ->
                new BreakdownRow(rs.getString("dimension_key"), rs.getString("dimension_label"), coverage(rs), metrics(rs)));
        boolean truncated = rows.size() > MAX_GROUPS;
        return new Breakdown(DashboardModels.filters(query), dimension,
                truncated ? rows.subList(0, MAX_GROUPS) : rows, truncated);
    }

    /**
     * Fetches exactly the requested dimension segments (by their {@code breakdown()}
     * key value, e.g. a Robot UUID string, {@code "NONE"}, {@code "MANUAL"}/{@code "ROBOT"},
     * or a provider name) in one query — used by Phase 13C explicit/automatic
     * insight comparisons so a two-segment comparison never needs the {@link #breakdown}
     * method's 100-group cap/truncation and never issues more than one query
     * regardless of how many distinct segments exist for this dimension overall.
     * A requested key with zero matching publications is simply absent from the
     * result (the caller treats a missing key as a zero-sample segment) rather
     * than a query error.
     */
    public List<BreakdownRow> segments(DashboardQuery query, Dimension dimension, List<String> keys, Instant now) {
        if (keys.isEmpty()) {
            return List.of();
        }
        String key = dimensionKeyExpr(dimension);
        String label = dimensionLabelExpr(dimension);
        String sql = observed(query) + "SELECT " + key + " AS dimension_key, " + label + " AS dimension_label, "
                + coverageSql() + ", " + metricSql() + " FROM observed "
                + "WHERE " + key + " IN (:segmentKeys) "
                + "GROUP BY " + key;
        MapSqlParameterSource params = params(query, now).addValue("segmentKeys", keys);
        return jdbc.query(sql, params, (rs, row) ->
                new BreakdownRow(rs.getString("dimension_key"), rs.getString("dimension_label"), coverage(rs), metrics(rs)));
    }

    private static String dimensionKeyExpr(Dimension dimension) {
        return switch (dimension) {
            case ROBOT -> "COALESCE(robot_id::text, 'NONE')";
            case PERSONA -> "COALESCE(persona_id::text, 'NONE')";
            case CONTENT_SOURCE -> "COALESCE(content_source_id::text, 'NONE')";
            case PROVIDER -> "provider";
            case ORIGIN -> "CASE WHEN robot_run_id IS NULL THEN 'MANUAL' ELSE 'ROBOT' END";
            case AI_USAGE -> "CASE WHEN applied_content_suggestion_id IS NULL THEN 'NO_APPLIED_AI' ELSE 'AI_APPLIED' END";
            case EXPERIMENT_VARIANT -> "COALESCE(experiment_variant_id::text, 'NONE')";
        };
    }

    /**
     * Grouping is always by {@code dimensionKeyExpr} alone (never key+label —
     * see the {@code GROUP BY} call sites), because a Robot/Persona/ContentSource
     * can legitimately carry more than one historical name snapshot across
     * publications that share the same underlying ID (it was renamed in
     * between) — Phase 13B correctly preserves each publication's own
     * snapshot, but a breakdown/segment row must still represent one entity,
     * not silently split its aggregate across as many rows as it has ever had
     * names. Every non-fixed-vocabulary label is therefore its own aggregate
     * expression, deterministically taking the label from that key's most
     * recently *published* row — the same "most recent wins" rule
     * {@link #options} already uses for its own name/label maps.
     */
    private static String dimensionLabelExpr(Dimension dimension) {
        String perPublicationLabel = switch (dimension) {
            case ROBOT -> "CASE WHEN robot_id IS NULL THEN 'Manual / No Robot' ELSE COALESCE(robot_name_snapshot, 'Robot name unavailable') END";
            case PERSONA -> "CASE WHEN persona_id IS NULL THEN 'No applied Persona' ELSE COALESCE(persona_name_snapshot, 'Persona name unavailable') END";
            case CONTENT_SOURCE -> "CASE WHEN content_source_id IS NULL THEN 'No ContentSource' ELSE COALESCE(content_source_name_snapshot, 'ContentSource name unavailable') END";
            case EXPERIMENT_VARIANT -> "CASE WHEN experiment_variant_id IS NULL THEN 'No experiment variant' ELSE COALESCE(experiment_variant_label_snapshot, 'Experiment variant unavailable') END";
            case PROVIDER, ORIGIN, AI_USAGE -> null;
        };
        return perPublicationLabel == null ? dimensionKeyExpr(dimension)
                : "(ARRAY_AGG(" + perPublicationLabel + " ORDER BY published_at DESC))[1]";
    }

    public FilterOptions options(DashboardQuery query) {
        String sql = """
                SELECT sa.platform, a.robot_id, a.robot_name_snapshot, a.persona_id,
                       a.persona_name_snapshot, a.content_source_id, a.content_source_name_snapshot
                FROM publications p
                JOIN social_accounts sa ON sa.id = p.social_account_id
                LEFT JOIN publication_attributions a ON a.publication_id = p.id AND a.workspace_id = p.workspace_id
                WHERE p.workspace_id = :workspaceId AND p.status = 'PUBLISHED'
                  AND p.published_at >= :fromInstant AND p.published_at < :toExclusive
                ORDER BY p.published_at DESC, p.id DESC
                LIMIT 5000
                """;
        var providers = new java.util.TreeSet<String>();
        var robots = new java.util.TreeMap<String, String>();
        var personas = new java.util.TreeMap<String, String>();
        var sources = new java.util.TreeMap<String, String>();
        var params = params(query, Instant.now());
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        for (var row : rows) {
            providers.add((String) row.get("platform"));
            addOption(robots, row, "robot_id", "robot_name_snapshot");
            addOption(personas, row, "persona_id", "persona_name_snapshot");
            addOption(sources, row, "content_source_id", "content_source_name_snapshot");
        }
        boolean truncated = rows.size() == 5000 || robots.size() > MAX_GROUPS
                || personas.size() > MAX_GROUPS || sources.size() > MAX_GROUPS;
        return new FilterOptions(new ArrayList<>(providers), options(robots), options(personas), options(sources), truncated);
    }

    private static void addOption(Map<String, String> target, Map<String, Object> row, String id, String label) {
        Object value = row.get(id);
        if (value != null) target.putIfAbsent(value.toString(), (String) row.getOrDefault(label, null));
    }

    private static List<Option> options(Map<String, String> values) {
        return values.entrySet().stream().limit(MAX_GROUPS)
                .map(entry -> new Option(entry.getKey(), entry.getValue() == null ? "Name unavailable" : entry.getValue()))
                .toList();
    }

    private static String observed(DashboardQuery query) {
        return """
                WITH cohort AS (
                    SELECT p.id, p.published_at, sa.platform AS provider, a.robot_id, a.robot_name_snapshot,
                           a.persona_id, a.persona_name_snapshot, a.content_source_id,
                           a.content_source_name_snapshot, a.robot_run_id, a.applied_content_suggestion_id,
                           a.experiment_variant_id, a.experiment_variant_label_snapshot
                    FROM publications p
                    JOIN social_accounts sa ON sa.id = p.social_account_id
                    LEFT JOIN publication_attributions a ON a.publication_id = p.id AND a.workspace_id = p.workspace_id
                    WHERE p.workspace_id = :workspaceId AND p.status = 'PUBLISHED'
                      AND p.published_at >= :fromInstant AND p.published_at < :toExclusive
                      AND (CAST(:provider AS text) IS NULL OR sa.platform = :provider)
                      AND (CAST(:robotId AS uuid) IS NULL OR a.robot_id = :robotId)
                      AND (CAST(:personaId AS uuid) IS NULL OR a.persona_id = :personaId)
                      AND (CAST(:contentSourceId AS uuid) IS NULL OR a.content_source_id = :contentSourceId)
                      AND (CAST(:origin AS text) IS NULL OR
                           (:origin = 'MANUAL' AND a.robot_run_id IS NULL) OR
                           (:origin = 'ROBOT' AND a.robot_run_id IS NOT NULL))
                      AND (CAST(:aiUsage AS text) IS NULL OR
                           (:aiUsage = 'AI_APPLIED' AND a.applied_content_suggestion_id IS NOT NULL) OR
                           (:aiUsage = 'NO_APPLIED_AI' AND a.applied_content_suggestion_id IS NULL))
                      AND (CAST(:experimentId AS uuid) IS NULL OR a.experiment_id = :experimentId)
                ), observed AS (
                    SELECT c.*, (c.published_at <= :matureBefore) AS eligible, s.id AS snapshot_id,
                           s.views, s.reach, s.likes, s.comments, s.shares, s.saves, s.total_interactions
                    FROM cohort c
                """ + snapshotLateralJoinSql(query.window(), "c.id", "c.published_at",
                        "s.views, s.reach, s.likes, s.comments, s.shares, s.saves, s.total_interactions", "s") + ") ";
    }

    /**
     * The one snapshot-selection algorithm: given a Publication (via
     * {@code publicationIdExpr}/{@code matureBeforeExpr}, both plain SQL
     * expressions evaluable in the enclosing scope) and a target observation
     * {@code window}, picks at most one {@code publication_analytics_snapshots}
     * row — nearest to the window's target age for H24/H72/D7, most recent
     * for LATEST — exactly like {@link #observed} always has. Package-public
     * (not just used within this class) specifically so
     * {@code com.fdmultimedia.api.experiments}'s Phase 14B statistical
     * analysis reuses this verbatim instead of writing a second
     * snapshot-selection query; only the selected metric columns and the
     * surrounding query shape (per-Publication here, per-ExperimentAssignment
     * there) ever differ.
     */
    public static String snapshotLateralJoinSql(
            Window window, String publicationIdExpr, String matureBeforeExpr, String selectColumnsSql, String resultAlias) {
        String snapshotFilter = window == Window.LATEST ? "" :
                " AND s.publication_age_seconds BETWEEN :minimumAge AND :maximumAge";
        String ordering = window == Window.LATEST ? "s.collected_at DESC, s.id DESC" :
                "ABS(s.publication_age_seconds - :targetAge), s.collected_at DESC, s.id DESC";
        return "LEFT JOIN LATERAL (SELECT s.id, " + selectColumnsSql
                + " FROM publication_analytics_snapshots s WHERE s.publication_id = " + publicationIdExpr
                + " AND " + matureBeforeExpr + " <= :matureBefore" + snapshotFilter
                + " ORDER BY " + ordering + " LIMIT 1) " + resultAlias + " ON TRUE";
    }

    private static String coverageSql() {
        return "COUNT(*) AS publication_count, COUNT(snapshot_id) AS analytics_publication_count, "
                + "COUNT(*) FILTER (WHERE eligible) AS eligible_by_age_count, "
                + "COUNT(*) FILTER (WHERE NOT eligible) AS too_young_count, "
                + "COUNT(*) FILTER (WHERE eligible AND snapshot_id IS NULL) AS missing_snapshot_count";
    }

    private static String metricSql() {
        List<String> fragments = new ArrayList<>();
        for (String metric : METRICS) fragments.add(aggregate(metric));
        return String.join(", ", fragments);
    }

    private static String aggregate(String column) {
        return "SUM(" + column + ") AS " + column + "_total, AVG(" + column + ") AS " + column
                + "_average, percentile_cont(0.5) WITHIN GROUP (ORDER BY " + column + ")::numeric AS "
                + column + "_median, COUNT(" + column + ") AS " + column + "_sample_count";
    }

    private static Coverage coverage(ResultSet rs) throws SQLException {
        return new Coverage(rs.getLong("publication_count"), rs.getLong("analytics_publication_count"),
                rs.getLong("eligible_by_age_count"), rs.getLong("too_young_count"), rs.getLong("missing_snapshot_count"));
    }

    private static Map<Metric, MetricAggregate> metrics(ResultSet rs) throws SQLException {
        Map<Metric, MetricAggregate> values = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) values.put(metric, metric(rs, metric));
        return values;
    }

    private static MetricAggregate metric(ResultSet rs, Metric metric) throws SQLException {
        String prefix = metric.column();
        return new MetricAggregate(rs.getBigDecimal(prefix + "_total"), rs.getBigDecimal(prefix + "_average"),
                rs.getBigDecimal(prefix + "_median"), rs.getLong(prefix + "_sample_count"));
    }

    private static MapSqlParameterSource params(DashboardQuery query, Instant now) {
        Instant matureBefore = query.window() == Window.LATEST ? now : now.minusSeconds(query.window().targetSeconds());
        return new MapSqlParameterSource()
                .addValue("workspaceId", query.workspaceId())
                .addValue("fromInstant", Timestamp.from(query.from().atStartOfDay().toInstant(ZoneOffset.UTC)))
                .addValue("toExclusive", Timestamp.from(query.to().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)))
                .addValue("matureBefore", Timestamp.from(matureBefore))
                .addValue("provider", query.provider())
                .addValue("robotId", query.robotId())
                .addValue("personaId", query.personaId())
                .addValue("contentSourceId", query.contentSourceId())
                .addValue("origin", query.origin() == null ? null : query.origin().name())
                .addValue("aiUsage", query.aiUsage() == null ? null : query.aiUsage().name())
                .addValue("experimentId", query.experimentId())
                .addValue("targetAge", query.window().targetSeconds())
                .addValue("minimumAge", query.window().minimumSeconds())
                .addValue("maximumAge", query.window().maximumSeconds());
    }
}
