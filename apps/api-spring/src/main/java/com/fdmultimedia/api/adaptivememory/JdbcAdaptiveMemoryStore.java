package com.fdmultimedia.api.adaptivememory;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdaptiveMemoryStore implements AdaptiveMemoryStore {
    /** Bound on the facts/events of a single Robot read at once (a Robot's adaptive history is small and bounded by horizons). */
    public static final int MAX_FACTS_PER_ROBOT = 5_000;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAdaptiveMemoryStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    private static Instant inst(ResultSet rs, String c) throws SQLException { Timestamp t = rs.getTimestamp(c); return t == null ? null : t.toInstant(); }
    private static UUID uuid(ResultSet rs, String c) throws SQLException { return rs.getObject(c, UUID.class); }

    @Override
    public void lockRobot(UUID robotId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key,0))",
                new MapSqlParameterSource("key", "adaptive-memory:" + robotId), rs -> {});
    }

    @Override
    public Optional<UUID> robotWorkspace(UUID robotId) {
        return jdbc.query("select workspace_id from robots where id=:id", new MapSqlParameterSource("id", robotId),
                (rs, n) -> uuid(rs, "workspace_id")).stream().findFirst();
    }

    @Override
    public Optional<UUID> currentPersona(UUID workspaceId, UUID robotId) {
        return jdbc.query("select persona_id from robots where id=:id and workspace_id=:w",
                new MapSqlParameterSource("id", robotId).addValue("w", workspaceId), (rs, n) -> uuid(rs, "persona_id"))
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public Optional<UUID> robotForReview(UUID workspaceId, UUID reviewId) {
        return jdbc.query("""
                select rr.robot_id from campaign_performance_reviews r join robot_runs rr on rr.id = r.robot_run_id
                where r.id = :review and r.workspace_id = :w
                """, new MapSqlParameterSource("review", reviewId).addValue("w", workspaceId), (rs, n) -> uuid(rs, "robot_id"))
                .stream().findFirst();
    }

    // ---- facts: every source is an existing immutable row; nothing is computed from analytics here ----

    private static final String FACTS_SQL = """
            select * from (
              -- 17H OptimizationProposal (Robot via its source review's run)
              select 'PROPOSAL_CREATED' event_type, 'OPTIMIZATION_PROPOSAL' source_type, p.id source_id, p.created_at occurred_at,
                     p.workspace_id, rr.robot_id, p.baseline_persona_id from_persona_id, p.candidate_persona_id to_persona_id,
                     null::uuid revision_id, p.materialized_experiment_id experiment_id, null::uuid evaluation_id,
                     null::uuid recommendation_id, null::text execution_origin, p.observation_window window_name, p.status::text detail
              from optimization_proposals p join campaign_performance_reviews r on r.id = p.source_review_id
              join robot_runs rr on rr.id = r.robot_run_id where rr.robot_id = :robot
              union all
              select 'PROPOSAL_REJECTED', 'OPTIMIZATION_PROPOSAL', p.id, p.reviewed_at, p.workspace_id, rr.robot_id,
                     p.baseline_persona_id, p.candidate_persona_id, null, p.materialized_experiment_id, null, null, null,
                     p.observation_window, p.status::text
              from optimization_proposals p join campaign_performance_reviews r on r.id = p.source_review_id
              join robot_runs rr on rr.id = r.robot_run_id
              where rr.robot_id = :robot and p.status = 'REJECTED' and p.reviewed_at is not null
              union all
              -- 17I RobotChangeProposal
              select 'CHANGE_PROPOSAL_CREATED', 'ROBOT_CHANGE_PROPOSAL', c.id, c.created_at, c.workspace_id, c.target_robot_id,
                     c.current_persona_id, c.proposed_persona_id, null, c.source_experiment_id, null, null, null,
                     c.observation_window, c.status::text
              from robot_change_proposals c where c.target_robot_id = :robot and c.current_persona_id is not null
              union all
              select 'PROPOSAL_REJECTED', 'ROBOT_CHANGE_PROPOSAL', c.id, c.reviewed_at, c.workspace_id, c.target_robot_id,
                     c.current_persona_id, c.proposed_persona_id, null, c.source_experiment_id, null, null, null,
                     c.observation_window, c.status::text
              from robot_change_proposals c
              where c.target_robot_id = :robot and c.current_persona_id is not null and c.status = 'REJECTED' and c.reviewed_at is not null
              union all
              select 'CHANGE_PROPOSAL_APPROVED', 'ROBOT_CHANGE_PROPOSAL', c.id, c.reviewed_at, c.workspace_id, c.target_robot_id,
                     c.current_persona_id, c.proposed_persona_id, null, c.source_experiment_id, null, null, null,
                     c.observation_window, c.status::text
              from robot_change_proposals c
              where c.target_robot_id = :robot and c.current_persona_id is not null and c.status <> 'REJECTED' and c.reviewed_at is not null
              union all
              -- 17I RobotConfigurationRevision: forward change, rollback of a forward change, supersession by a later forward change
              select 'CHANGE_APPLIED', 'ROBOT_CONFIGURATION_REVISION', v.id, v.created_at, v.workspace_id, v.robot_id,
                     v.previous_persona_id, v.new_persona_id, v.id, v.source_experiment_id, null, null, v.execution_origin::text,
                     null, v.change_type::text
              from robot_configuration_revisions v
              where v.robot_id = :robot and v.change_type = 'PERSONA_CHANGE' and v.previous_persona_id is not null and v.new_persona_id is not null
              union all
              select 'CHANGE_ROLLED_BACK', 'ROBOT_CONFIGURATION_REVISION', rb.id, rb.created_at, rb.workspace_id, rb.robot_id,
                     t.previous_persona_id, t.new_persona_id, t.id, t.source_experiment_id, null, null, rb.execution_origin::text,
                     null, rb.change_type::text
              from robot_configuration_revisions rb join robot_configuration_revisions t on t.id = rb.rollback_of_revision_id
              where rb.robot_id = :robot and rb.change_type = 'ROLLBACK' and t.previous_persona_id is not null and t.new_persona_id is not null
              union all
              select 'TRANSITION_SUPERSEDED', 'ROBOT_CONFIGURATION_REVISION', n.id, n.created_at, t.workspace_id, t.robot_id,
                     t.previous_persona_id, t.new_persona_id, t.id, t.source_experiment_id, null, null, n.execution_origin::text,
                     null, n.change_type::text
              from robot_configuration_revisions t join robot_configuration_revisions n on n.robot_id = t.robot_id and n.revision = t.revision + 1
              where t.robot_id = :robot and t.change_type = 'PERSONA_CHANGE' and n.change_type = 'PERSONA_CHANGE'
                and t.previous_persona_id is not null and t.new_persona_id is not null
              union all
              -- 17M decision-window safety evaluations: the first observation of each (monitor, window, ready status)
              select * from (
                select distinct on (e.monitor_id, e.observation_window, e.status)
                       case when e.status = 'READY_STABLE' then 'SAFETY_STABLE' else 'SAFETY_REGRESSION' end,
                       'POST_CHANGE_SAFETY_EVALUATION', e.id, e.evaluated_at, e.workspace_id, e.robot_id,
                       m.previous_persona_id, m.new_persona_id, m.revision_id, m.source_experiment_id, e.id, null::uuid,
                       m.execution_origin::text, e.observation_window::text, e.status::text
                from post_change_safety_evaluations e join post_change_safety_monitors m on m.id = e.monitor_id
                where e.robot_id = :robot and e.informational = false and e.status in ('READY_STABLE', 'READY_REGRESSION_OBSERVED')
                  and m.previous_persona_id is not null
                order by e.monitor_id, e.observation_window, e.status, e.evaluation_revision
              ) safety
              union all
              -- 17M RollbackRecommendation lifecycle facts (never an outcome change)
              select 'ROLLBACK_RECOMMENDED', 'ROLLBACK_RECOMMENDATION', q.id, q.created_at, q.workspace_id, q.robot_id,
                     q.previous_persona_id, q.current_persona_id, q.revision_id, null, q.evaluation_id, q.id,
                     q.execution_origin::text, q.observation_window::text, q.status::text
              from rollback_recommendations q where q.robot_id = :robot and q.previous_persona_id is not null
              union all
              select 'ROLLBACK_DISMISSED', 'ROLLBACK_RECOMMENDATION', q.id, q.dismissed_at, q.workspace_id, q.robot_id,
                     q.previous_persona_id, q.current_persona_id, q.revision_id, null, q.evaluation_id, q.id,
                     q.execution_origin::text, q.observation_window::text, q.status::text
              from rollback_recommendations q
              where q.robot_id = :robot and q.previous_persona_id is not null and q.dismissed_at is not null
            ) facts where from_persona_id <> to_persona_id order by occurred_at, source_type, source_id, event_type limit :limit
            """;

    private Fact fact(ResultSet rs, int n) throws SQLException {
        return new Fact(EventType.valueOf(rs.getString("event_type")), SourceType.valueOf(rs.getString("source_type")), uuid(rs, "source_id"),
                inst(rs, "occurred_at"), uuid(rs, "workspace_id"), uuid(rs, "robot_id"), uuid(rs, "from_persona_id"), uuid(rs, "to_persona_id"),
                uuid(rs, "revision_id"), uuid(rs, "experiment_id"), uuid(rs, "evaluation_id"), uuid(rs, "recommendation_id"),
                rs.getString("execution_origin"), rs.getString("window_name"), rs.getString("detail"));
    }

    @Override
    public List<Fact> collectFacts(UUID robotId) {
        return jdbc.query(FACTS_SQL, new MapSqlParameterSource("robot", robotId).addValue("limit", MAX_FACTS_PER_ROBOT), this::fact);
    }

    @Override
    public int insertMissingEvents(Collection<Fact> facts, Instant now) {
        int inserted = 0;
        for (Fact f : facts) {
            inserted += jdbc.update("""
                    insert into robot_adaptive_transition_memory_events (id, workspace_id, robot_id, from_persona_id, to_persona_id, event_type,
                      source_type, source_id, occurred_at, engine_version, revision_id, experiment_id, evaluation_id, recommendation_id,
                      execution_origin, observation_window, detail, projected_at)
                    values (:id,:w,:robot,:from,:to,:type,:stype,:sid,:at,:engine,:rev,:exp,:eval,:rec,:origin,:win,:detail,:now)
                    on conflict (source_type, source_id, event_type) do nothing
                    """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("w", f.workspaceId()).addValue("robot", f.robotId())
                    .addValue("from", f.fromPersonaId()).addValue("to", f.toPersonaId()).addValue("type", f.type().name())
                    .addValue("stype", f.sourceType().name()).addValue("sid", f.sourceId()).addValue("at", ts(f.occurredAt()))
                    .addValue("engine", AdaptiveMemoryModels.ENGINE_VERSION).addValue("rev", f.revisionId()).addValue("exp", f.experimentId())
                    .addValue("eval", f.evaluationId()).addValue("rec", f.recommendationId()).addValue("origin", f.executionOrigin())
                    .addValue("win", f.window()).addValue("detail", f.detail() == null ? null : f.detail().substring(0, Math.min(64, f.detail().length())))
                    .addValue("now", ts(now)));
        }
        return inserted;
    }

    @Override
    public List<Fact> loadEvents(UUID robotId) {
        return jdbc.query("""
                select event_type, source_type, source_id, occurred_at, workspace_id, robot_id, from_persona_id, to_persona_id, revision_id,
                       experiment_id, evaluation_id, recommendation_id, execution_origin, observation_window window_name, detail
                from robot_adaptive_transition_memory_events where robot_id = :robot
                order by occurred_at, source_type, source_id, event_type limit :limit
                """, new MapSqlParameterSource("robot", robotId).addValue("limit", MAX_FACTS_PER_ROBOT), this::fact);
    }

    // ---- projection rows ----

    private Memory memory(ResultSet rs, int n) throws SQLException {
        return new Memory(uuid(rs, "workspace_id"), uuid(rs, "robot_id"), uuid(rs, "from_persona_id"), uuid(rs, "to_persona_id"),
                Outcome.valueOf(rs.getString("latest_outcome")), rs.getInt("proposal_count"), rs.getInt("apply_count"),
                rs.getInt("rollback_count"), rs.getInt("regression_count"), inst(rs, "first_seen_at"), inst(rs, "last_seen_at"),
                inst(rs, "last_proposed_at"), inst(rs, "last_rejected_at"), inst(rs, "last_applied_at"), inst(rs, "last_regression_at"),
                inst(rs, "last_rolled_back_at"), inst(rs, "latest_evidence_at"), uuid(rs, "latest_revision_id"), uuid(rs, "latest_evaluation_id"),
                uuid(rs, "latest_recommendation_id"), uuid(rs, "latest_experiment_id"), rs.getString("latest_safety_status"), rs.getInt("event_count"));
    }

    @Override
    public Map<AdaptiveMemoryProjector.Key, Memory> loadMemory(UUID robotId) {
        Map<AdaptiveMemoryProjector.Key, Memory> result = new LinkedHashMap<>();
        jdbc.query("select * from robot_adaptive_transition_memory where robot_id = :robot order by last_seen_at desc, id",
                new MapSqlParameterSource("robot", robotId), this::memory)
                .forEach(m -> result.put(new AdaptiveMemoryProjector.Key(m.robotId(), m.fromPersonaId(), m.toPersonaId()), m));
        return result;
    }

    @Override
    public boolean upsertMemory(Memory m, Instant now) {
        Memory existing = loadOne(m.robotId(), m.fromPersonaId(), m.toPersonaId());
        if (m.equals(existing)) return false;
        jdbc.update("""
                insert into robot_adaptive_transition_memory (id, workspace_id, robot_id, from_persona_id, to_persona_id, factor, engine_version,
                  latest_outcome, latest_safety_status, proposal_count, apply_count, rollback_count, regression_count, event_count, first_seen_at,
                  last_seen_at, last_proposed_at, last_rejected_at, last_applied_at, last_regression_at, last_rolled_back_at, latest_evidence_at,
                  latest_revision_id, latest_evaluation_id, latest_recommendation_id, latest_experiment_id, last_projected_at)
                values (:id,:w,:robot,:from,:to,'PERSONA',:engine,:outcome,:safety,:pc,:ac,:rc,:gc,:ec,:first,:last,:lp,:lr,:la,:lg,:lb,:le,
                  :rev,:eval,:rec,:exp,:now)
                on conflict (robot_id, from_persona_id, to_persona_id) do update set engine_version=excluded.engine_version,
                  latest_outcome=excluded.latest_outcome, latest_safety_status=excluded.latest_safety_status,
                  proposal_count=excluded.proposal_count, apply_count=excluded.apply_count, rollback_count=excluded.rollback_count,
                  regression_count=excluded.regression_count, event_count=excluded.event_count, first_seen_at=excluded.first_seen_at,
                  last_seen_at=excluded.last_seen_at, last_proposed_at=excluded.last_proposed_at, last_rejected_at=excluded.last_rejected_at,
                  last_applied_at=excluded.last_applied_at, last_regression_at=excluded.last_regression_at,
                  last_rolled_back_at=excluded.last_rolled_back_at, latest_evidence_at=excluded.latest_evidence_at,
                  latest_revision_id=excluded.latest_revision_id, latest_evaluation_id=excluded.latest_evaluation_id,
                  latest_recommendation_id=excluded.latest_recommendation_id, latest_experiment_id=excluded.latest_experiment_id,
                  last_projected_at=excluded.last_projected_at
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("w", m.workspaceId()).addValue("robot", m.robotId())
                .addValue("from", m.fromPersonaId()).addValue("to", m.toPersonaId()).addValue("engine", AdaptiveMemoryModels.ENGINE_VERSION)
                .addValue("outcome", m.latestOutcome().name()).addValue("safety", m.latestSafetyStatus()).addValue("pc", m.proposalCount())
                .addValue("ac", m.applyCount()).addValue("rc", m.rollbackCount()).addValue("gc", m.regressionCount()).addValue("ec", m.eventCount())
                .addValue("first", ts(m.firstSeenAt())).addValue("last", ts(m.lastSeenAt())).addValue("lp", ts(m.lastProposedAt()))
                .addValue("lr", ts(m.lastRejectedAt())).addValue("la", ts(m.lastAppliedAt())).addValue("lg", ts(m.lastRegressionAt()))
                .addValue("lb", ts(m.lastRolledBackAt())).addValue("le", ts(m.latestEvidenceAt())).addValue("rev", m.latestRevisionId())
                .addValue("eval", m.latestEvaluationId()).addValue("rec", m.latestRecommendationId()).addValue("exp", m.latestExperimentId())
                .addValue("now", ts(now)));
        return true;
    }

    private Memory loadOne(UUID robotId, UUID from, UUID to) {
        return jdbc.query("select * from robot_adaptive_transition_memory where robot_id=:r and from_persona_id=:f and to_persona_id=:t",
                new MapSqlParameterSource("r", robotId).addValue("f", from).addValue("t", to), this::memory).stream().findFirst().orElse(null);
    }

    @Override
    public void touchState(UUID robotId, UUID workspaceId, int eventCount, Instant now) {
        jdbc.update("""
                insert into robot_adaptive_memory_state (robot_id, workspace_id, last_reconciled_at, event_count) values (:r,:w,:now,:ec)
                on conflict (robot_id) do update set last_reconciled_at = excluded.last_reconciled_at, event_count = excluded.event_count
                """, new MapSqlParameterSource("r", robotId).addValue("w", workspaceId).addValue("now", ts(now)).addValue("ec", eventCount));
    }

    @Override
    public List<UUID> robotsNeedingReconciliation(Instant staleBefore, int limit) {
        return jdbc.query("""
                select r.id from robots r left join robot_adaptive_memory_state s on s.robot_id = r.id
                where (s.robot_id is null or s.last_reconciled_at < :before)
                  and (exists (select 1 from robot_configuration_revisions v where v.robot_id = r.id)
                    or exists (select 1 from robot_change_proposals c where c.target_robot_id = r.id)
                    or exists (select 1 from optimization_proposals p join campaign_performance_reviews cr on cr.id = p.source_review_id
                               join robot_runs rr on rr.id = cr.robot_run_id where rr.robot_id = r.id))
                order by s.last_reconciled_at nulls first, r.id limit :limit
                """, new MapSqlParameterSource("before", ts(staleBefore)).addValue("limit", limit), (rs, n) -> uuid(rs, "id"));
    }

    @Override
    public Map<UUID, String> personaNames(Collection<UUID> personaIds) {
        Map<UUID, String> names = new HashMap<>();
        if (personaIds.isEmpty()) return names;
        jdbc.query("select id, name from personas where id in (:ids)", new MapSqlParameterSource("ids", personaIds),
                rs -> { names.put(uuid(rs, "id"), rs.getString("name")); });
        return names;
    }
}
