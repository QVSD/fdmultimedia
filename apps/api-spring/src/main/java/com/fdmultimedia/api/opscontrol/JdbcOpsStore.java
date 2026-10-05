package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels;
import com.fdmultimedia.api.opscontrol.OpsFacts.*;
import com.fdmultimedia.api.opscontrol.OpsModels.*;
import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import com.fdmultimedia.api.shared.operations.SchedulerStatusSink;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation. Queries are aggregates or limited, indexed lookups; nothing selects payloads, results, captions, tokens or
 * provider identifiers. Failure messages are sanitized before they leave this class.
 */
@Repository
public class JdbcOpsStore implements OpsStore, SchedulerStatusSink {
    private static final String INCIDENT_COLUMNS = """
            id, incident_key, severity, status, category, title, condition_code, detail, suggested_action, subject_type, subject_id,
            first_observed_at, last_observed_at, resolved_at, acknowledged_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOpsStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    private static Instant inst(ResultSet rs, String c) throws SQLException { Timestamp t = rs.getTimestamp(c); return t == null ? null : t.toInstant(); }
    private static UUID uuid(ResultSet rs, String c) throws SQLException { return rs.getObject(c, UUID.class); }
    private static Long nullableLong(ResultSet rs, String c) throws SQLException { long v = rs.getLong(c); return rs.wasNull() ? null : v; }
    private static MapSqlParameterSource params(UUID workspaceId) { return new MapSqlParameterSource("w", workspaceId); }
    private static int clampPage(int page) { return Math.max(0, page); }
    private static int clampSize(int size) { return Math.max(1, Math.min(size, MAX_PAGE)); }

    // ---- scheduler status sink (per instance) ----

    @Override
    public void record(String scheduler, String instanceId, SchedulerOperationTracker.Snapshot s) {
        String state = s.state();
        if (!"RUNNING".equals(state) && !"SUCCEEDED".equals(state) && !"FAILED".equals(state)) return;
        jdbc.update("""
                insert into operations_scheduler_status (scheduler_name, instance_id, state, last_started_at, last_completed_at,
                    last_succeeded_at, last_failed_at, last_duration_ms, processed_count, result_count, last_failure_code, updated_at)
                values (:name, :instance, :state, :started, :completed, :succeeded, :failed, :duration, :processed, :results, :code, now())
                on conflict (scheduler_name, instance_id) do update set state = excluded.state,
                    last_started_at = excluded.last_started_at, last_completed_at = excluded.last_completed_at,
                    last_succeeded_at = excluded.last_succeeded_at, last_failed_at = excluded.last_failed_at,
                    last_duration_ms = excluded.last_duration_ms, processed_count = excluded.processed_count,
                    result_count = excluded.result_count, last_failure_code = excluded.last_failure_code, updated_at = now()
                """, new MapSqlParameterSource().addValue("name", scheduler).addValue("instance", instanceId).addValue("state", state)
                .addValue("started", ts(s.lastStartedAt())).addValue("completed", ts(s.lastCompletedAt()))
                .addValue("succeeded", ts(s.lastSucceededAt())).addValue("failed", ts(s.lastFailedAt()))
                .addValue("duration", s.lastDurationMs()).addValue("processed", s.processedCount()).addValue("results", s.resultCount())
                .addValue("code", s.lastFailureCode() == null ? null : s.lastFailureCode().substring(0, Math.min(120, s.lastFailureCode().length()))));
    }

    @Override
    public List<SchedulerInstance> schedulerInstances() {
        return jdbc.query("""
                select scheduler_name, instance_id, state, last_started_at, last_completed_at, last_succeeded_at, last_failed_at,
                       last_duration_ms, processed_count, result_count, last_failure_code, updated_at
                from operations_scheduler_status order by scheduler_name, instance_id limit 1000
                """, new MapSqlParameterSource(), (rs, n) -> new SchedulerInstance(rs.getString("scheduler_name"),
                rs.getString("instance_id"), rs.getString("state"), inst(rs, "last_started_at"), inst(rs, "last_completed_at"),
                inst(rs, "last_succeeded_at"), inst(rs, "last_failed_at"), nullableLong(rs, "last_duration_ms"),
                rs.getLong("processed_count"), rs.getLong("result_count"), rs.getString("last_failure_code"), inst(rs, "updated_at")));
    }

    @Override
    public int purgeSchedulerStatus(Instant before) {
        return jdbc.update("delete from operations_scheduler_status where updated_at < :before", new MapSqlParameterSource("before", ts(before)));
    }

    @Override
    public List<UUID> workspaceIds(int limit) {
        return jdbc.query("select id from workspaces order by created_at, id limit :limit",
                new MapSqlParameterSource("limit", limit), (rs, n) -> uuid(rs, "id"));
    }

    @Override
    public boolean tryAdvisoryLock(String key) {
        Boolean acquired = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", key), Boolean.class);
        return Boolean.TRUE.equals(acquired);
    }

    // ---- workers ----

    @Override
    public List<WorkerFact> workers(UUID workspaceId, Instant recentSince) {
        List<WorkerFact> rows = jdbc.query("""
                select w.id, w.name, w.last_seen_at, coalesce(w.active_jobs, 0) as active_jobs, coalesce(w.max_active_jobs, 1) as max_active_jobs,
                       coalesce((select array_agg(x) from jsonb_array_elements_text(w.current_supported_job_types) x), '{}') as capabilities,
                       w.agent_version,
                       (select j.id from jobs j where j.assigned_worker_id = w.id and j.status in ('ASSIGNED', 'RUNNING')
                        order by j.assigned_at desc nulls last limit 1) as current_job_id
                from workers w where w.workspace_id = :w order by w.name, w.id limit :limit
                """, params(workspaceId).addValue("limit", MAX_WORKERS), (rs, n) -> {
            java.sql.Array array = rs.getArray("capabilities");
            List<String> capabilities = array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
            return new WorkerFact(uuid(rs, "id"), rs.getString("name"), inst(rs, "last_seen_at"), rs.getInt("active_jobs"),
                    rs.getInt("max_active_jobs"), capabilities, rs.getString("agent_version"), uuid(rs, "current_job_id"), null, null);
        });
        if (rows.isEmpty()) return rows;
        var outcomes = new java.util.HashMap<UUID, Instant[]>();
        jdbc.query("""
                select assigned_worker_id,
                       max(finished_at) filter (where status = 'SUCCEEDED') as last_ok,
                       max(finished_at) filter (where status = 'FAILED') as last_failed
                from jobs where workspace_id = :w and finished_at >= :since and assigned_worker_id is not null
                group by assigned_worker_id
                """, params(workspaceId).addValue("since", ts(recentSince)), rs -> {
            outcomes.put(uuid(rs, "assigned_worker_id"), new Instant[] {inst(rs, "last_ok"), inst(rs, "last_failed")});
        });
        List<WorkerFact> result = new ArrayList<>();
        for (WorkerFact row : rows) {
            Instant[] o = outcomes.get(row.id());
            result.add(new WorkerFact(row.id(), row.name(), row.lastSeenAt(), row.activeJobs(), row.maxActiveJobs(), row.capabilities(),
                    row.agentVersion(), row.currentJobId(), o == null ? null : o[0], o == null ? null : o[1]));
        }
        return result;
    }

    // ---- jobs ----

    @Override
    public JobFacts jobFacts(UUID workspaceId, Instant now, Instant since, Instant burstSince) {
        return jdbc.queryForObject("""
                select count(*) filter (where status = 'QUEUED') as queued,
                       count(*) filter (where status = 'ASSIGNED') as assigned,
                       count(*) filter (where status = 'RUNNING') as running,
                       count(*) filter (where status in ('ASSIGNED', 'RUNNING') and lease_expires_at < :now) as lease_expired,
                       count(*) filter (where status = 'FAILED' and finished_at >= :since) as failed_recent,
                       count(*) filter (where status = 'SUCCEEDED' and finished_at >= :since) as succeeded_recent,
                       count(*) filter (where status = 'FAILED' and finished_at >= :burst) as failed_burst,
                       min(queued_at) filter (where status = 'QUEUED') as oldest_queued
                from jobs where workspace_id = :w and (status in ('QUEUED', 'ASSIGNED', 'RUNNING') or finished_at >= :since)
                """, params(workspaceId).addValue("now", ts(now)).addValue("since", ts(since)).addValue("burst", ts(burstSince)),
                (rs, n) -> new JobFacts(rs.getLong("queued"), rs.getLong("assigned"), rs.getLong("running"), rs.getLong("lease_expired"),
                        rs.getLong("failed_recent"), rs.getLong("succeeded_recent"), rs.getLong("failed_burst"), inst(rs, "oldest_queued")));
    }

    private static JobRow jobRow(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        int attempts = rs.getInt("attempt_count");
        String retryState = "FAILED".equals(status) ? "EXHAUSTED" : "QUEUED".equals(status) && attempts > 0 ? "RETRY_PENDING" : "NONE";
        boolean failure = "FAILED".equals(status) || ("QUEUED".equals(status) && attempts > 0);
        return new JobRow(uuid(rs, "id"), rs.getString("type"), status, inst(rs, "queued_at"), inst(rs, "finished_at"), attempts,
                rs.getInt("max_attempts"), retryState, failure ? OpsSanitizer.category(rs.getString("error_code")) : null,
                failure ? OpsSanitizer.message(rs.getString("error_message")) : null, inst(rs, "lease_expires_at"));
    }

    @Override
    public Page<JobRow> jobs(UUID workspaceId, String status, String type, int page, int size) {
        int p = clampPage(page), s = clampSize(size);
        MapSqlParameterSource source = params(workspaceId).addValue("status", status).addValue("type", type)
                .addValue("limit", s).addValue("offset", (long) p * s);
        String where = " where workspace_id = :w and (cast(:status as text) is null or status = :status) and (cast(:type as text) is null or type = :type) ";
        long total = jdbc.queryForObject("select count(*) from jobs" + where, source, Long.class);
        List<JobRow> items = jdbc.query("""
                select id, type, status, queued_at, finished_at, attempt_count, max_attempts, error_code, error_message, lease_expires_at
                from jobs""" + where + " order by queued_at desc, id desc limit :limit offset :offset", source, (rs, n) -> jobRow(rs));
        return new Page<>(items, p, s, total);
    }

    @Override
    public Optional<JobDetail> job(UUID workspaceId, UUID jobId) {
        MapSqlParameterSource source = params(workspaceId).addValue("id", jobId);
        Optional<JobDetail> head = jdbc.query("""
                select j.id, j.type, j.status, j.queued_at, j.finished_at, j.attempt_count, j.max_attempts, j.error_code, j.error_message,
                       j.lease_expires_at, j.assigned_at, j.started_at, w.name as worker_name
                from jobs j left join workers w on w.id = j.assigned_worker_id
                where j.workspace_id = :w and j.id = :id
                """, source, (rs, n) -> new JobDetail(jobRow(rs), rs.getString("worker_name"), inst(rs, "assigned_at"),
                inst(rs, "started_at"), List.of())).stream().findFirst();
        if (head.isEmpty()) return head;
        List<AttemptRow> attempts = jdbc.query("""
                select a.job_attempt, a.outcome, a.error_code, a.started_at, a.finished_at
                from publishing_attempts a join publications p on p.id = a.publication_id
                where a.job_id = :id and p.workspace_id = :w order by a.job_attempt, a.created_at limit 50
                """, source, (rs, n) -> new AttemptRow(rs.getInt("job_attempt"), rs.getString("outcome"),
                OpsSanitizer.category(rs.getString("error_code")), inst(rs, "started_at"), inst(rs, "finished_at")));
        JobDetail d = head.get();
        return Optional.of(new JobDetail(d.job(), d.workerName(), d.assignedAt(), d.startedAt(), attempts));
    }

    // ---- publishing ----

    @Override
    public PublishingFacts publishingFacts(UUID workspaceId, Instant now, Instant since, Instant burstSince, Instant overdueBefore) {
        MapSqlParameterSource source = params(workspaceId).addValue("now", ts(now)).addValue("since", ts(since)).addValue("burst", ts(burstSince))
                .addValue("overdue", ts(overdueBefore));
        long[] due = new long[2];
        Instant[] oldest = new Instant[1];
        jdbc.query("""
                select count(*) as due, count(*) filter (where scheduled_for <= :overdue) as overdue, min(scheduled_for) as oldest from publish_schedules
                where workspace_id = :w and status = 'SCHEDULED' and scheduled_for <= :now
                """, source, rs -> { due[0] = rs.getLong("due"); due[1] = rs.getLong("overdue"); oldest[0] = inst(rs, "oldest"); });
        return jdbc.queryForObject("""
                select count(*) filter (where status = 'PUBLISHING') as publishing,
                       count(*) filter (where status = 'PUBLISHED' and published_at >= :since) as published_recent,
                       count(*) filter (where status = 'FAILED' and updated_at >= :since) as failed_recent,
                       count(*) filter (where status = 'FAILED' and updated_at >= :burst) as failed_burst,
                       (select count(*) from publication_provider_states s join publications pp on pp.id = s.publication_id
                        where pp.workspace_id = :w and s.state = 'OUTCOME_UNKNOWN') as outcome_unknown
                from publications where workspace_id = :w and (status = 'PUBLISHING' or created_at >= :since or updated_at >= :since)
                """, source, (rs, n) -> new PublishingFacts(due[0], due[1], rs.getLong("publishing"), rs.getLong("published_recent"),
                rs.getLong("failed_recent"), rs.getLong("failed_burst"), rs.getLong("outcome_unknown"), oldest[0]));
    }

    @Override
    public List<ProviderFact> providers(UUID workspaceId, Instant since) {
        return jdbc.query("""
                with w as (
                    select a.platform, p.status, p.published_at, p.updated_at
                    from publications p join social_accounts a on a.id = p.social_account_id
                    where p.workspace_id = :w and p.created_at >= :since),
                s as (
                    select platform, max(published_at) filter (where status = 'PUBLISHED') as last_ok,
                           max(updated_at) filter (where status = 'FAILED') as last_failed
                    from w group by platform)
                select s.platform, s.last_ok, s.last_failed,
                       (select count(*) from w where w.platform = s.platform and w.status = 'FAILED'
                        and (s.last_ok is null or w.updated_at > s.last_ok)) as failures
                from s order by s.platform
                """, params(workspaceId).addValue("since", ts(since)), (rs, n) -> new ProviderFact(rs.getString("platform"),
                inst(rs, "last_ok"), inst(rs, "last_failed"), rs.getLong("failures")));
    }

    @Override
    public Page<PublicationRow> publicationAttention(UUID workspaceId, Instant since, int page, int size) {
        int p = clampPage(page), s = clampSize(size);
        MapSqlParameterSource source = params(workspaceId).addValue("since", ts(since)).addValue("limit", s).addValue("offset", (long) p * s);
        String from = """
                from publications p join social_accounts a on a.id = p.social_account_id
                where p.workspace_id = :w and (
                    (p.status = 'FAILED' and p.updated_at >= :since)
                    or exists (select 1 from publication_provider_states ps where ps.publication_id = p.id and ps.state = 'OUTCOME_UNKNOWN'))
                """;
        long total = jdbc.queryForObject("select count(*) " + from, source, Long.class);
        List<PublicationRow> items = jdbc.query("""
                select p.id, a.platform, p.status, p.created_at, p.updated_at, p.failure_code,
                       (select count(*) from publishing_attempts pa where pa.publication_id = p.id) as attempts,
                       exists (select 1 from publication_provider_states ps where ps.publication_id = p.id and ps.state = 'OUTCOME_UNKNOWN') as unknown
                """ + from + " order by p.updated_at desc, p.id desc limit :limit offset :offset", source, (rs, n) -> {
            boolean unknown = rs.getBoolean("unknown");
            return new PublicationRow(uuid(rs, "id"), rs.getString("platform"), rs.getString("status"), inst(rs, "created_at"),
                    "PUBLISHING".equals(rs.getString("status")) ? null : inst(rs, "updated_at"), rs.getInt("attempts"),
                    OpsSanitizer.category(rs.getString("failure_code")), unknown,
                    unknown ? "VERIFY_WITH_PROVIDER_BEFORE_ANY_RETRY" : "NO_AUTOMATIC_RETRY");
        });
        return new Page<>(items, p, s, total);
    }

    // ---- automation ----

    @Override
    public AutomationFacts automationFacts(UUID workspaceId, Instant now, Instant since) {
        MapSqlParameterSource source = params(workspaceId).addValue("now", ts(now)).addValue("since", ts(since))
                .addValue("proposed", ts(now.minus(AdaptiveMemoryModels.PROPOSAL_SUPPRESSION)))
                .addValue("rejected", ts(now.minus(AdaptiveMemoryModels.REJECTION_SUPPRESSION)))
                .addValue("applied", ts(now.minus(AdaptiveMemoryModels.APPLIED_SUPPRESSION)))
                .addValue("regression", ts(now.minus(AdaptiveMemoryModels.REGRESSION_SUPPRESSION)))
                .addValue("rolledBack", ts(now.minus(AdaptiveMemoryModels.ROLLBACK_SUPPRESSION)));
        return jdbc.queryForObject("""
                select
                  (select count(*) from robot_adaptive_policies where workspace_id = :w and enabled
                       and proposal_automation_mode = 'AUTO_PROPOSE') as auto_propose,
                  (select count(*) from robot_change_proposals where workspace_id = :w
                       and status in ('READY_FOR_REVIEW', 'APPROVED')) as pending,
                  (select count(*) from robot_adaptive_execution_authorizations where workspace_id = :w
                       and status = 'ACTIVE' and expires_at > :now) as authorizations,
                  (select count(*) from (select distinct on (robot_id) eligible from adaptive_guardrail_evaluations
                       where workspace_id = :w and evaluated_at >= :since order by robot_id, evaluated_at desc) latest
                       where not latest.eligible) as blocked,
                  (select count(*) from post_change_safety_monitors where workspace_id = :w and status = 'MONITORING') as observing,
                  (select count(*) from rollback_recommendations where workspace_id = :w and status in ('OPEN', 'ACKNOWLEDGED')) as rollback,
                  (select count(*) from robot_adaptive_transition_memory where workspace_id = :w and (
                       last_proposed_at > :proposed or last_rejected_at > :rejected or last_applied_at > :applied
                       or last_regression_at > :regression or last_rolled_back_at > :rolledBack)) as suppressed
                """, source, (rs, n) -> new AutomationFacts(rs.getLong("auto_propose"), rs.getLong("pending"), rs.getLong("authorizations"),
                rs.getLong("blocked"), rs.getLong("observing"), rs.getLong("rollback"), rs.getLong("suppressed")));
    }

    @Override
    public List<RollbackFact> openRollbackRecommendations(UUID workspaceId, int limit) {
        return jdbc.query("""
                select id, created_at, status from rollback_recommendations
                where workspace_id = :w and status = 'OPEN' order by created_at desc, id desc limit :limit
                """, params(workspaceId).addValue("limit", limit),
                (rs, n) -> new RollbackFact(uuid(rs, "id"), inst(rs, "created_at"), rs.getString("status")));
    }

    // ---- incidents ----

    private static Incident incident(ResultSet rs) throws SQLException {
        Instant acknowledgedAt = inst(rs, "acknowledged_at");
        return new Incident(uuid(rs, "id"), rs.getString("incident_key"), Severity.valueOf(rs.getString("severity")),
                IncidentStatus.valueOf(rs.getString("status")), rs.getString("category"), rs.getString("title"),
                rs.getString("condition_code"), rs.getString("detail"), rs.getString("suggested_action"), rs.getString("subject_type"),
                uuid(rs, "subject_id"), inst(rs, "first_observed_at"), inst(rs, "last_observed_at"), inst(rs, "resolved_at"),
                acknowledgedAt, acknowledgedAt != null, true);
    }

    @Override
    public List<Incident> activeIncidents(UUID workspaceId) {
        return jdbc.query("select " + INCIDENT_COLUMNS + " from operations_incidents where workspace_id = :w and status = 'ACTIVE' "
                + "order by first_observed_at, id limit " + MAX_ACTIVE_INCIDENTS, params(workspaceId), (rs, n) -> incident(rs));
    }

    @Override
    public Page<Incident> incidents(UUID workspaceId, String status, Severity severity, int page, int size) {
        int p = clampPage(page), s = clampSize(size);
        MapSqlParameterSource source = params(workspaceId).addValue("status", status).addValue("severity", severity == null ? null : severity.name())
                .addValue("limit", s).addValue("offset", (long) p * s);
        String where = " where workspace_id = :w and (cast(:status as text) is null or status = :status) "
                + "and (cast(:severity as text) is null or severity = :severity) ";
        long total = jdbc.queryForObject("select count(*) from operations_incidents" + where, source, Long.class);
        List<Incident> items = jdbc.query("select " + INCIDENT_COLUMNS + " from operations_incidents" + where + """
                order by case status when 'ACTIVE' then 0 else 1 end,
                         case severity when 'CRITICAL' then 0 when 'WARNING' then 1 else 2 end,
                         first_observed_at desc, id desc
                limit :limit offset :offset
                """, source, (rs, n) -> incident(rs));
        return new Page<>(items, p, s, total);
    }

    @Override
    public Optional<Incident> incident(UUID workspaceId, UUID incidentId) {
        return jdbc.query("select " + INCIDENT_COLUMNS + " from operations_incidents where workspace_id = :w and id = :id",
                params(workspaceId).addValue("id", incidentId), (rs, n) -> incident(rs)).stream().findFirst();
    }

    private static MapSqlParameterSource derived(DerivedIncident d, Instant now) {
        return new MapSqlParameterSource().addValue("key", d.key()).addValue("severity", d.severity().name())
                .addValue("category", d.category()).addValue("title", d.title()).addValue("code", d.conditionCode())
                .addValue("detail", d.detail()).addValue("action", d.suggestedAction()).addValue("subjectType", d.subjectType())
                .addValue("subjectId", d.subjectId()).addValue("now", ts(now));
    }

    @Override
    public boolean insertIncident(UUID workspaceId, DerivedIncident d, Instant now) {
        return jdbc.update("""
                insert into operations_incidents (id, workspace_id, incident_key, engine_version, severity, status, category, title,
                    condition_code, detail, suggested_action, subject_type, subject_id, first_observed_at, last_observed_at)
                values (:id, :w, :key, :engine, :severity, 'ACTIVE', :category, :title, :code, :detail, :action, :subjectType, :subjectId, :now, :now)
                on conflict (workspace_id, incident_key) where status = 'ACTIVE' do nothing
                """, derived(d, now).addValue("id", UUID.randomUUID()).addValue("w", workspaceId)
                .addValue("engine", OpsModels.INCIDENT_ENGINE)) > 0;
    }

    @Override
    public void updateIncident(UUID incidentId, DerivedIncident d, Instant now) {
        jdbc.update("""
                update operations_incidents set severity = :severity, category = :category, title = :title, condition_code = :code,
                    detail = :detail, suggested_action = :action, subject_type = :subjectType, subject_id = :subjectId,
                    last_observed_at = :now
                where id = :id and status = 'ACTIVE'
                """, derived(d, now).addValue("id", incidentId));
    }

    @Override
    public void touchIncident(UUID incidentId, Instant now) {
        jdbc.update("update operations_incidents set last_observed_at = :now where id = :id and status = 'ACTIVE'",
                new MapSqlParameterSource("id", incidentId).addValue("now", ts(now)));
    }

    @Override
    public void resolveIncident(UUID incidentId, Instant now) {
        jdbc.update("""
                update operations_incidents set status = 'RESOLVED', resolved_at = greatest(:now, first_observed_at),
                    last_observed_at = greatest(:now, last_observed_at)
                where id = :id and status = 'ACTIVE'
                """, new MapSqlParameterSource("id", incidentId).addValue("now", ts(now)));
    }

    @Override
    public boolean acknowledgeIncident(UUID workspaceId, UUID incidentId, UUID userId, Instant now) {
        return jdbc.update("""
                update operations_incidents set acknowledged_at = :now, acknowledged_by_user_id = :user
                where workspace_id = :w and id = :id and status = 'ACTIVE' and acknowledged_at is null
                """, params(workspaceId).addValue("id", incidentId).addValue("user", userId).addValue("now", ts(now))) > 0;
    }

    @Override
    public int purgeResolvedIncidents(Instant before) {
        return jdbc.update("delete from operations_incidents where status = 'RESOLVED' and resolved_at < :before",
                new MapSqlParameterSource("before", ts(before)));
    }
}
