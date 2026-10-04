package com.fdmultimedia.api.safety;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.analytics.PublicationDashboardStore;
import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ExecutionOrigin;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSafetyStore implements SafetyStore {
    /** The post-change cohort is bounded: at most this many RobotRuns of a single epoch horizon are read. */
    public static final int MAX_COHORT_ROWS = 5_000;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSafetyStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ---- helpers ----

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    private static Instant inst(ResultSet rs, String c) throws SQLException { Timestamp t = rs.getTimestamp(c); return t == null ? null : t.toInstant(); }
    private static UUID uuid(ResultSet rs, String c) throws SQLException { return rs.getObject(c, UUID.class); }
    private static Integer integer(ResultSet rs, String c) throws SQLException { int v = rs.getInt(c); return rs.wasNull() ? null : v; }
    private static <E extends Enum<E>> E en(Class<E> type, String v) { return v == null ? null : Enum.valueOf(type, v); }

    // ---- monitors ----

    @Override
    public void lockRevision(UUID revisionId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key,0))",
                new MapSqlParameterSource("key", "post-change-safety:" + revisionId), rs -> {});
    }

    private static final String MONITOR_COLUMNS = "id, workspace_id, robot_id, revision_id, robot_revision, execution_origin, "
            + "execution_authorization_id, source_proposal_id, source_experiment_id, previous_persona_id, previous_persona_name, "
            + "new_persona_id, new_persona_name, metric, epoch_start, epoch_end, status, completed_reason, created_at, last_evaluated_at";

    private MonitorRecord monitor(ResultSet rs, int n) throws SQLException {
        return new MonitorRecord(uuid(rs, "id"), uuid(rs, "workspace_id"), uuid(rs, "robot_id"), uuid(rs, "revision_id"),
                rs.getInt("robot_revision"), ExecutionOrigin.valueOf(rs.getString("execution_origin")), uuid(rs, "execution_authorization_id"),
                uuid(rs, "source_proposal_id"), uuid(rs, "source_experiment_id"), uuid(rs, "previous_persona_id"),
                rs.getString("previous_persona_name"), uuid(rs, "new_persona_id"), rs.getString("new_persona_name"),
                en(Metric.class, rs.getString("metric")), inst(rs, "epoch_start"), inst(rs, "epoch_end"),
                MonitorStatus.valueOf(rs.getString("status")), en(CompletedReason.class, rs.getString("completed_reason")),
                inst(rs, "created_at"), inst(rs, "last_evaluated_at"));
    }

    @Override
    public Optional<MonitorRecord> findMonitor(UUID workspaceId, UUID revisionId) {
        return jdbc.query("select " + MONITOR_COLUMNS + " from post_change_safety_monitors where workspace_id=:w and revision_id=:r",
                new MapSqlParameterSource("w", workspaceId).addValue("r", revisionId), this::monitor).stream().findFirst();
    }

    @Override
    public void insertMonitor(MonitorRecord m, Instant now) {
        jdbc.update("""
                insert into post_change_safety_monitors (id, workspace_id, robot_id, revision_id, robot_revision, factor, execution_origin,
                  execution_authorization_id, source_proposal_id, source_experiment_id, previous_persona_id, previous_persona_name,
                  new_persona_id, new_persona_name, metric, epoch_start, epoch_end, status, completed_reason, engine_version,
                  created_at, last_evaluated_at, updated_at)
                values (:id,:w,:robot,:rev,:revn,'PERSONA',:origin,:auth,:proposal,:exp,:prev,:prevName,:newp,:newName,:metric,:start,:end,
                  :status,:reason,:engine,:created,:last,:now)
                """, new MapSqlParameterSource("id", m.id()).addValue("w", m.workspaceId()).addValue("robot", m.robotId())
                .addValue("rev", m.revisionId()).addValue("revn", m.robotRevision()).addValue("origin", m.executionOrigin().name())
                .addValue("auth", m.authorizationId()).addValue("proposal", m.proposalId()).addValue("exp", m.experimentId())
                .addValue("prev", m.previousPersonaId()).addValue("prevName", m.previousPersonaName()).addValue("newp", m.newPersonaId())
                .addValue("newName", m.newPersonaName()).addValue("metric", m.metric() == null ? null : m.metric().name())
                .addValue("start", ts(m.epochStart())).addValue("end", ts(m.epochEnd())).addValue("status", m.status().name())
                .addValue("reason", m.completedReason() == null ? null : m.completedReason().name())
                .addValue("engine", PostChangeSafetyModels.ENGINE_VERSION).addValue("created", ts(m.createdAt()))
                .addValue("last", ts(m.lastEvaluatedAt())).addValue("now", ts(now)));
    }

    @Override
    public void touchMonitor(UUID monitorId, Instant now) {
        jdbc.update("update post_change_safety_monitors set last_evaluated_at=:now, updated_at=:now where id=:id",
                new MapSqlParameterSource("id", monitorId).addValue("now", ts(now)));
    }

    @Override
    public void closeMonitor(UUID monitorId, MonitorStatus status, CompletedReason reason, Instant epochEnd, Instant now) {
        jdbc.update("""
                update post_change_safety_monitors set status=:status, completed_reason=:reason,
                  epoch_end=coalesce(:end, epoch_end), last_evaluated_at=:now, updated_at=:now
                where id=:id and status='MONITORING'
                """, new MapSqlParameterSource("id", monitorId).addValue("status", status.name()).addValue("reason", reason.name())
                .addValue("end", ts(epochEnd)).addValue("now", ts(now)));
    }

    @Override
    public List<UUID> monitorableRevisionIds(Instant now, int limit) {
        return jdbc.query("""
                select r.id from robot_configuration_revisions r
                left join post_change_safety_monitors m on m.revision_id = r.id
                where r.change_type = 'PERSONA_CHANGE' and r.execution_origin in ('HUMAN_APPLY','PREAUTHORIZED_AUTO_APPLY')
                  and r.created_at > :since and (m.id is null or m.status = 'MONITORING')
                order by m.last_evaluated_at nulls first, r.created_at
                limit :limit
                """, new MapSqlParameterSource("since", ts(now.minus(PostChangeSafetyService.MONITOR_HORIZON.plusDays(1))))
                .addValue("limit", limit), (rs, n) -> uuid(rs, "id"));
    }

    @Override
    public List<MonitorRecord> monitorsForRobot(UUID workspaceId, UUID robotId, int limit) {
        return jdbc.query("select " + MONITOR_COLUMNS + " from post_change_safety_monitors where workspace_id=:w and robot_id=:r "
                + "order by robot_revision desc limit :limit", new MapSqlParameterSource("w", workspaceId).addValue("r", robotId)
                .addValue("limit", limit), this::monitor);
    }

    // ---- baselines ----

    private BaselineRecord baseline(ResultSet rs, int n) throws SQLException {
        return new BaselineRecord(uuid(rs, "id"), uuid(rs, "workspace_id"), uuid(rs, "monitor_id"), Window.valueOf(rs.getString("observation_window")),
                uuid(rs, "source_experiment_id"), rs.getString("analysis_engine_version"), Metric.valueOf(rs.getString("metric")),
                rs.getString("provider"), rs.getInt("assignment_count"), rs.getInt("eligible_count"), rs.getInt("sample_count"),
                rs.getBigDecimal("eligible_coverage"), rs.getBigDecimal("assignment_coverage"), rs.getBigDecimal("baseline_mean"),
                rs.getString("baseline_fingerprint"), inst(rs, "frozen_at"));
    }

    @Override
    public Optional<BaselineRecord> findBaseline(UUID monitorId, Window window) {
        return jdbc.query("select * from post_change_safety_baselines where monitor_id=:m and observation_window=:w",
                new MapSqlParameterSource("m", monitorId).addValue("w", window.name()), this::baseline).stream().findFirst();
    }

    @Override
    public List<BaselineRecord> baselines(UUID monitorId) {
        return jdbc.query("select * from post_change_safety_baselines where monitor_id=:m order by observation_window",
                new MapSqlParameterSource("m", monitorId), this::baseline);
    }

    @Override
    public void insertBaseline(BaselineRecord b) {
        jdbc.update("""
                insert into post_change_safety_baselines (id, workspace_id, monitor_id, observation_window, source_experiment_id,
                  analysis_engine_version, metric, provider, assignment_count, eligible_count, sample_count, eligible_coverage,
                  assignment_coverage, baseline_mean, baseline_fingerprint, frozen_at)
                values (:id,:w,:m,:win,:exp,:engine,:metric,:provider,:ac,:ec,:sc,:ecov,:acov,:mean,:fp,:at)
                on conflict (monitor_id, observation_window) do nothing
                """, new MapSqlParameterSource("id", b.id()).addValue("w", b.workspaceId()).addValue("m", b.monitorId())
                .addValue("win", b.window().name()).addValue("exp", b.experimentId()).addValue("engine", b.analysisEngineVersion())
                .addValue("metric", b.metric().name()).addValue("provider", b.provider()).addValue("ac", b.assignmentCount())
                .addValue("ec", b.eligibleCount()).addValue("sc", b.sample()).addValue("ecov", b.eligibleCoverage())
                .addValue("acov", b.assignmentCoverage()).addValue("mean", b.mean()).addValue("fp", b.fingerprint())
                .addValue("at", ts(b.frozenAt())));
    }

    // ---- evaluations ----

    private EvaluationRecord evaluation(ResultSet rs, int n) throws SQLException {
        String reasons = rs.getString("reason_codes");
        return new EvaluationRecord(uuid(rs, "id"), uuid(rs, "workspace_id"), uuid(rs, "monitor_id"), uuid(rs, "robot_id"),
                uuid(rs, "revision_id"), rs.getInt("evaluation_revision"), rs.getString("engine_version"),
                Window.valueOf(rs.getString("observation_window")), rs.getBoolean("informational"),
                EvaluationStatus.valueOf(rs.getString("status")), reasons.isBlank() ? List.of() : Arrays.asList(reasons.split(",")),
                Trigger.valueOf(rs.getString("trigger_type")), en(Metric.class, rs.getString("metric")), rs.getString("provider"),
                en(AdverseDirection.class, rs.getString("adverse_direction")), ExecutionOrigin.valueOf(rs.getString("execution_origin")),
                uuid(rs, "execution_authorization_id"), uuid(rs, "source_proposal_id"), uuid(rs, "source_experiment_id"),
                uuid(rs, "previous_persona_id"), uuid(rs, "new_persona_id"), uuid(rs, "baseline_id"), integer(rs, "baseline_sample"),
                rs.getBigDecimal("baseline_coverage"), rs.getBigDecimal("baseline_value"), inst(rs, "epoch_start"), inst(rs, "epoch_end"),
                inst(rs, "cohort_end"), rs.getInt("post_runs"), rs.getInt("post_published"), rs.getInt("post_eligible"),
                rs.getInt("post_sample"), rs.getBigDecimal("post_coverage"), rs.getBigDecimal("post_value"),
                rs.getBigDecimal("absolute_difference"), rs.getBigDecimal("relative_difference_percent"),
                rs.getBigDecimal("material_threshold_percent"), rs.getInt("min_sample"), rs.getBigDecimal("min_coverage"),
                rs.getString("evidence_fingerprint"), inst(rs, "evaluated_at"));
    }

    @Override
    public Optional<EvaluationRecord> findEvaluationByFingerprint(UUID monitorId, Window window, String fingerprint) {
        return jdbc.query("select * from post_change_safety_evaluations where monitor_id=:m and observation_window=:w and evidence_fingerprint=:f",
                new MapSqlParameterSource("m", monitorId).addValue("w", window.name()).addValue("f", fingerprint), this::evaluation).stream().findFirst();
    }

    @Override
    public int nextEvaluationRevision(UUID monitorId, Window window) {
        Integer max = jdbc.queryForObject("select coalesce(max(evaluation_revision),0) from post_change_safety_evaluations where monitor_id=:m and observation_window=:w",
                new MapSqlParameterSource("m", monitorId).addValue("w", window.name()), Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    @Override
    public void insertEvaluation(EvaluationRecord e) {
        jdbc.update("""
                insert into post_change_safety_evaluations (id, workspace_id, monitor_id, robot_id, revision_id, evaluation_revision,
                  engine_version, observation_window, informational, status, reason_codes, trigger_type, metric, provider,
                  adverse_direction, execution_origin, execution_authorization_id, source_proposal_id, source_experiment_id,
                  previous_persona_id, new_persona_id, baseline_id, baseline_sample, baseline_coverage, baseline_value, epoch_start,
                  epoch_end, cohort_end, post_runs, post_published, post_eligible, post_sample, post_coverage, post_value,
                  absolute_difference, relative_difference_percent, material_threshold_percent, min_sample, min_coverage,
                  evidence_fingerprint, evaluated_at)
                values (:id,:w,:m,:robot,:rev,:erev,:engine,:win,:info,:status,:reasons,:trigger,:metric,:provider,:dir,:origin,:auth,
                  :proposal,:exp,:prev,:newp,:bid,:bs,:bc,:bv,:es,:ee,:ce,:pr,:pp,:pe,:ps,:pc,:pv,:ad,:rd,:mt,:ms,:mc,:fp,:at)
                """, new MapSqlParameterSource("id", e.id()).addValue("w", e.workspaceId()).addValue("m", e.monitorId())
                .addValue("robot", e.robotId()).addValue("rev", e.revisionId()).addValue("erev", e.evaluationRevision())
                .addValue("engine", e.engineVersion()).addValue("win", e.window().name()).addValue("info", e.informational())
                .addValue("status", e.status().name()).addValue("reasons", String.join(",", e.reasons()))
                .addValue("trigger", e.trigger().name()).addValue("metric", e.metric() == null ? null : e.metric().name())
                .addValue("provider", e.provider()).addValue("dir", e.direction() == null ? null : e.direction().name())
                .addValue("origin", e.executionOrigin().name()).addValue("auth", e.authorizationId())
                .addValue("proposal", e.proposalId()).addValue("exp", e.experimentId()).addValue("prev", e.previousPersonaId())
                .addValue("newp", e.newPersonaId()).addValue("bid", e.baselineId()).addValue("bs", e.baselineSample())
                .addValue("bc", e.baselineCoverage()).addValue("bv", e.baselineValue()).addValue("es", ts(e.epochStart()))
                .addValue("ee", ts(e.epochEnd())).addValue("ce", ts(e.cohortEnd())).addValue("pr", e.postRuns())
                .addValue("pp", e.postPublished()).addValue("pe", e.postEligible()).addValue("ps", e.postSample())
                .addValue("pc", e.postCoverage()).addValue("pv", e.postValue()).addValue("ad", e.absoluteDifference())
                .addValue("rd", e.relativeDifferencePercent()).addValue("mt", e.materialThresholdPercent())
                .addValue("ms", e.minSample()).addValue("mc", e.minCoverage()).addValue("fp", e.evidenceFingerprint())
                .addValue("at", ts(e.evaluatedAt())));
    }

    @Override
    public List<EvaluationRecord> listEvaluations(UUID workspaceId, UUID revisionId, int limit) {
        return jdbc.query("select * from post_change_safety_evaluations where workspace_id=:w and revision_id=:r "
                + "order by evaluated_at desc, evaluation_revision desc limit :limit",
                new MapSqlParameterSource("w", workspaceId).addValue("r", revisionId).addValue("limit", limit), this::evaluation);
    }

    @Override
    public List<EvaluationRecord> latestEvaluations(UUID monitorId) {
        return jdbc.query("""
                select distinct on (observation_window) * from post_change_safety_evaluations where monitor_id=:m
                order by observation_window, evaluation_revision desc
                """, new MapSqlParameterSource("m", monitorId), this::evaluation);
    }

    @Override
    public Optional<EvaluationRecord> getEvaluation(UUID workspaceId, UUID id) {
        return jdbc.query("select * from post_change_safety_evaluations where workspace_id=:w and id=:id",
                new MapSqlParameterSource("w", workspaceId).addValue("id", id), this::evaluation).stream().findFirst();
    }

    // ---- recommendations ----

    private RecommendationRecord recommendation(ResultSet rs, int n) throws SQLException {
        return new RecommendationRecord(uuid(rs, "id"), uuid(rs, "workspace_id"), uuid(rs, "robot_id"), uuid(rs, "revision_id"),
                rs.getInt("robot_revision"), uuid(rs, "monitor_id"), uuid(rs, "evaluation_id"),
                Window.valueOf(rs.getString("observation_window")), RecommendationStatus.valueOf(rs.getString("status")),
                Metric.valueOf(rs.getString("metric")), rs.getString("provider"), uuid(rs, "previous_persona_id"),
                rs.getString("previous_persona_name"), uuid(rs, "current_persona_id"), rs.getString("current_persona_name"),
                ExecutionOrigin.valueOf(rs.getString("execution_origin")), uuid(rs, "execution_authorization_id"),
                rs.getInt("baseline_sample"), rs.getBigDecimal("baseline_value"), rs.getInt("post_sample"),
                rs.getBigDecimal("post_coverage"), rs.getBigDecimal("post_value"), rs.getBigDecimal("absolute_difference"),
                rs.getBigDecimal("relative_difference_percent"), rs.getString("reason"), rs.getString("limitations"),
                inst(rs, "created_at"), inst(rs, "acknowledged_at"), inst(rs, "dismissed_at"), inst(rs, "resolved_at"),
                uuid(rs, "rollback_revision_id"));
    }

    private Optional<RecommendationRecord> one(String where, MapSqlParameterSource p) {
        return jdbc.query("select * from rollback_recommendations where " + where + " limit 1", p, this::recommendation).stream().findFirst();
    }

    @Override
    public Optional<RecommendationRecord> findActiveRecommendation(UUID revisionId) {
        return one("revision_id=:r and status in ('OPEN','ACKNOWLEDGED')", new MapSqlParameterSource("r", revisionId));
    }

    @Override
    public Optional<RecommendationRecord> findRecommendationForWindow(UUID revisionId, Window window) {
        return one("revision_id=:r and observation_window=:w", new MapSqlParameterSource("r", revisionId).addValue("w", window.name()));
    }

    @Override
    public Optional<RecommendationRecord> findLatestRecommendation(UUID revisionId) {
        return jdbc.query("select * from rollback_recommendations where revision_id=:r order by created_at desc limit 1",
                new MapSqlParameterSource("r", revisionId), this::recommendation).stream().findFirst();
    }

    @Override
    public void insertRecommendation(RecommendationRecord r) {
        jdbc.update("""
                insert into rollback_recommendations (id, workspace_id, robot_id, revision_id, robot_revision, monitor_id, evaluation_id,
                  observation_window, status, metric, provider, previous_persona_id, previous_persona_name, current_persona_id,
                  current_persona_name, execution_origin, execution_authorization_id, baseline_sample, baseline_value, post_sample,
                  post_coverage, post_value, absolute_difference, relative_difference_percent, reason, limitations, created_at)
                values (:id,:w,:robot,:rev,:revn,:m,:eval,:win,'OPEN',:metric,:provider,:prev,:prevName,:cur,:curName,:origin,:auth,
                  :bs,:bv,:ps,:pc,:pv,:ad,:rd,:reason,:lim,:at)
                """, new MapSqlParameterSource("id", r.id()).addValue("w", r.workspaceId()).addValue("robot", r.robotId())
                .addValue("rev", r.revisionId()).addValue("revn", r.robotRevision()).addValue("m", r.monitorId())
                .addValue("eval", r.evaluationId()).addValue("win", r.window().name()).addValue("metric", r.metric().name())
                .addValue("provider", r.provider()).addValue("prev", r.previousPersonaId()).addValue("prevName", r.previousPersonaName())
                .addValue("cur", r.currentPersonaId()).addValue("curName", r.currentPersonaName())
                .addValue("origin", r.executionOrigin().name()).addValue("auth", r.authorizationId())
                .addValue("bs", r.baselineSample()).addValue("bv", r.baselineValue()).addValue("ps", r.postSample())
                .addValue("pc", r.postCoverage()).addValue("pv", r.postValue()).addValue("ad", r.absoluteDifference())
                .addValue("rd", r.relativeDifferencePercent()).addValue("reason", r.reason()).addValue("lim", r.limitations())
                .addValue("at", ts(r.createdAt())));
    }

    @Override
    public Optional<RecommendationRecord> lockRecommendation(UUID workspaceId, UUID id) {
        return jdbc.query("select * from rollback_recommendations where workspace_id=:w and id=:id for update",
                new MapSqlParameterSource("w", workspaceId).addValue("id", id), this::recommendation).stream().findFirst();
    }

    @Override
    public Optional<RecommendationRecord> getRecommendation(UUID workspaceId, UUID id) {
        return one("workspace_id=:w and id=:id", new MapSqlParameterSource("w", workspaceId).addValue("id", id));
    }

    @Override
    public List<RecommendationRecord> listRecommendations(UUID workspaceId, Collection<RecommendationStatus> statuses, UUID robotId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("w", workspaceId).addValue("limit", limit);
        StringBuilder sql = new StringBuilder("select * from rollback_recommendations where workspace_id=:w");
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" and status in (:statuses)");
            p.addValue("statuses", statuses.stream().map(Enum::name).toList());
        }
        if (robotId != null) { sql.append(" and robot_id=:robot"); p.addValue("robot", robotId); }
        sql.append(" order by created_at desc limit :limit");
        return jdbc.query(sql.toString(), p, this::recommendation);
    }

    @Override
    public void updateRecommendation(UUID id, RecommendationStatus status, Instant now, UUID userId, UUID rollbackRevisionId) {
        jdbc.update("""
                update rollback_recommendations set status=:status,
                  acknowledged_at = case when :status='ACKNOWLEDGED' then :now else acknowledged_at end,
                  acknowledged_by_user_id = case when :status='ACKNOWLEDGED' then :user else acknowledged_by_user_id end,
                  dismissed_at = case when :status='DISMISSED' then :now else dismissed_at end,
                  dismissed_by_user_id = case when :status='DISMISSED' then :user else dismissed_by_user_id end,
                  resolved_at = case when :status in ('DISMISSED','ROLLED_BACK','SUPERSEDED') then :now else resolved_at end,
                  rollback_revision_id = case when :status='ROLLED_BACK' then :rb else rollback_revision_id end
                where id=:id
                """, new MapSqlParameterSource("id", id).addValue("status", status.name()).addValue("now", ts(now))
                .addValue("user", userId).addValue("rb", rollbackRevisionId));
    }

    // ---- cohort ----

    @Override
    public List<CohortRow> postChangeRows(UUID workspaceId, UUID robotId, UUID personaId, Window window, Metric metric,
            Instant epochStart, Instant cohortEnd, Instant now) {
        String snapshotJoin = PublicationDashboardStore.snapshotLateralJoinSql(window, "sel.publication_id", "sel.published_at",
                "s." + metric.column() + " AS metric_value", "snap");
        String sql = """
                WITH run_publications AS (
                    SELECT rr.id AS run_id, p.id AS publication_id, p.published_at, sa.platform AS provider,
                           ROW_NUMBER() OVER (PARTITION BY rr.id ORDER BY p.published_at ASC NULLS LAST, p.id ASC) AS rn
                    FROM robot_runs rr
                    LEFT JOIN publication_attributions a ON a.robot_run_id = rr.id AND a.workspace_id = :workspace
                    LEFT JOIN publications p ON p.id = a.publication_id AND p.status = 'PUBLISHED'
                    LEFT JOIN social_accounts sa ON sa.id = p.social_account_id
                    WHERE rr.workspace_id = :workspace AND rr.robot_id = :robot AND rr.persona_id_snapshot = :persona
                      AND rr.experiment_assignment_id IS NULL AND rr.created_at >= :epochStart AND rr.created_at < :cohortEnd
                ), sel AS (
                    SELECT run_id, publication_id, published_at, provider,
                           (published_at IS NOT NULL AND published_at <= :matureBefore) AS eligible_by_age
                    FROM run_publications WHERE rn = 1
                )
                SELECT sel.run_id, sel.publication_id, sel.published_at, sel.provider, sel.eligible_by_age,
                       snap.id AS snapshot_id, snap.metric_value AS metric_value
                FROM sel
                """ + snapshotJoin + " ORDER BY sel.run_id LIMIT :limit";
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("workspace", workspaceId).addValue("robot", robotId)
                .addValue("persona", personaId).addValue("epochStart", ts(epochStart)).addValue("cohortEnd", ts(cohortEnd))
                .addValue("matureBefore", ts(now.minusSeconds(window.targetSeconds()))).addValue("targetAge", window.targetSeconds())
                .addValue("minimumAge", window.minimumSeconds()).addValue("maximumAge", window.maximumSeconds())
                .addValue("limit", MAX_COHORT_ROWS);
        return jdbc.query(sql, p, (rs, n) -> new CohortRow(uuid(rs, "run_id"), uuid(rs, "publication_id"), inst(rs, "published_at"),
                rs.getString("provider"), rs.getBoolean("eligible_by_age"), uuid(rs, "snapshot_id"), rs.getBigDecimal("metric_value")));
    }
}
