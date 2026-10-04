package com.fdmultimedia.api.safety;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** In-memory SafetyStore that mirrors the V42 uniqueness rules, for service-level unit tests. */
final class FakeSafetyStore implements SafetyStore {
    final Map<UUID, MonitorRecord> monitors = new LinkedHashMap<>();
    final List<BaselineRecord> baselines = new ArrayList<>();
    final List<EvaluationRecord> evaluations = new ArrayList<>();
    final Map<UUID, RecommendationRecord> recommendations = new LinkedHashMap<>();
    Function<Window, List<CohortRow>> cohort = w -> List.of();
    int cohortQueries;
    Window lastWindow;
    Instant lastCohortEnd;
    Instant lastEpochStart;
    UUID lastPersona;

    @Override public void lockRevision(UUID revisionId) {}

    @Override public Optional<MonitorRecord> findMonitor(UUID workspaceId, UUID revisionId) {
        return monitors.values().stream().filter(m -> m.workspaceId().equals(workspaceId) && m.revisionId().equals(revisionId)).findFirst();
    }
    @Override public void insertMonitor(MonitorRecord m, Instant now) {
        if (monitors.values().stream().anyMatch(x -> x.revisionId().equals(m.revisionId()))) throw new IllegalStateException("unique revision");
        monitors.put(m.id(), m);
    }
    private MonitorRecord copy(MonitorRecord m, Instant epochEnd, MonitorStatus status, CompletedReason reason, Instant last) {
        return new MonitorRecord(m.id(), m.workspaceId(), m.robotId(), m.revisionId(), m.robotRevision(), m.executionOrigin(),
                m.authorizationId(), m.proposalId(), m.experimentId(), m.previousPersonaId(), m.previousPersonaName(), m.newPersonaId(),
                m.newPersonaName(), m.metric(), m.epochStart(), epochEnd, status, reason, m.createdAt(), last);
    }
    @Override public void touchMonitor(UUID id, Instant now) {
        MonitorRecord m = monitors.get(id);
        monitors.put(id, copy(m, m.epochEnd(), m.status(), m.completedReason(), now));
    }
    @Override public void closeMonitor(UUID id, MonitorStatus status, CompletedReason reason, Instant epochEnd, Instant now) {
        MonitorRecord m = monitors.get(id);
        if (m.status() != MonitorStatus.MONITORING) return;
        monitors.put(id, copy(m, epochEnd == null ? m.epochEnd() : epochEnd, status, reason, now));
    }
    @Override public Optional<BaselineRecord> findBaseline(UUID monitorId, Window window) {
        return baselines.stream().filter(b -> b.monitorId().equals(monitorId) && b.window() == window).findFirst();
    }
    @Override public List<BaselineRecord> baselines(UUID monitorId) { return baselines.stream().filter(b -> b.monitorId().equals(monitorId)).toList(); }
    @Override public void insertBaseline(BaselineRecord b) { if (findBaseline(b.monitorId(), b.window()).isEmpty()) baselines.add(b); }
    @Override public Optional<EvaluationRecord> findEvaluationByFingerprint(UUID monitorId, Window window, String fp) {
        return evaluations.stream().filter(e -> e.monitorId().equals(monitorId) && e.window() == window && e.evidenceFingerprint().equals(fp)).findFirst();
    }
    @Override public int nextEvaluationRevision(UUID monitorId, Window window) {
        return evaluations.stream().filter(e -> e.monitorId().equals(monitorId) && e.window() == window).mapToInt(EvaluationRecord::evaluationRevision).max().orElse(0) + 1;
    }
    @Override public void insertEvaluation(EvaluationRecord e) {
        if (findEvaluationByFingerprint(e.monitorId(), e.window(), e.evidenceFingerprint()).isPresent()) throw new IllegalStateException("unique fingerprint");
        evaluations.add(e);
    }
    @Override public List<EvaluationRecord> listEvaluations(UUID workspaceId, UUID revisionId, int limit) {
        List<EvaluationRecord> rows = new ArrayList<>(evaluations.stream().filter(e -> e.revisionId().equals(revisionId)).toList());
        Collections.reverse(rows);
        return rows.stream().limit(limit).toList();
    }
    @Override public List<EvaluationRecord> latestEvaluations(UUID monitorId) {
        Map<Window, EvaluationRecord> latest = new EnumMap<>(Window.class);
        evaluations.stream().filter(e -> e.monitorId().equals(monitorId))
                .forEach(e -> latest.merge(e.window(), e, (a, b) -> a.evaluationRevision() >= b.evaluationRevision() ? a : b));
        return List.copyOf(latest.values());
    }
    @Override public Optional<EvaluationRecord> getEvaluation(UUID workspaceId, UUID id) {
        return evaluations.stream().filter(e -> e.id().equals(id) && e.workspaceId().equals(workspaceId)).findFirst();
    }

    @Override public Optional<RecommendationRecord> findActiveRecommendation(UUID revisionId) {
        return recommendations.values().stream().filter(r -> r.revisionId().equals(revisionId) && r.status().actionable()).findFirst();
    }
    @Override public Optional<RecommendationRecord> findRecommendationForWindow(UUID revisionId, Window window) {
        return recommendations.values().stream().filter(r -> r.revisionId().equals(revisionId) && r.window() == window).findFirst();
    }
    @Override public Optional<RecommendationRecord> findLatestRecommendation(UUID revisionId) {
        return recommendations.values().stream().filter(r -> r.revisionId().equals(revisionId)).reduce((a, b) -> b);
    }
    @Override public void insertRecommendation(RecommendationRecord r) {
        if (findActiveRecommendation(r.revisionId()).isPresent()) throw new IllegalStateException("one actionable per revision");
        if (findRecommendationForWindow(r.revisionId(), r.window()).isPresent()) throw new IllegalStateException("one per window");
        recommendations.put(r.id(), r);
    }
    @Override public Optional<RecommendationRecord> lockRecommendation(UUID workspaceId, UUID id) { return getRecommendation(workspaceId, id); }
    @Override public Optional<RecommendationRecord> getRecommendation(UUID workspaceId, UUID id) {
        return Optional.ofNullable(recommendations.get(id)).filter(r -> r.workspaceId().equals(workspaceId));
    }
    @Override public List<RecommendationRecord> listRecommendations(UUID workspaceId, Collection<RecommendationStatus> statuses, UUID robotId, int limit) {
        return recommendations.values().stream().filter(r -> r.workspaceId().equals(workspaceId))
                .filter(r -> statuses == null || statuses.isEmpty() || statuses.contains(r.status()))
                .filter(r -> robotId == null || r.robotId().equals(robotId)).limit(limit).toList();
    }
    @Override public void updateRecommendation(UUID id, RecommendationStatus status, Instant now, UUID userId, UUID rollbackRevisionId) {
        RecommendationRecord r = recommendations.get(id);
        recommendations.put(id, new RecommendationRecord(r.id(), r.workspaceId(), r.robotId(), r.revisionId(), r.robotRevision(), r.monitorId(),
                r.evaluationId(), r.window(), status, r.metric(), r.provider(), r.previousPersonaId(), r.previousPersonaName(), r.currentPersonaId(),
                r.currentPersonaName(), r.executionOrigin(), r.authorizationId(), r.baselineSample(), r.baselineValue(), r.postSample(),
                r.postCoverage(), r.postValue(), r.absoluteDifference(), r.relativeDifferencePercent(), r.reason(), r.limitations(), r.createdAt(),
                status == RecommendationStatus.ACKNOWLEDGED ? now : r.acknowledgedAt(), status == RecommendationStatus.DISMISSED ? now : r.dismissedAt(),
                status.actionable() ? null : now, status == RecommendationStatus.ROLLED_BACK ? rollbackRevisionId : r.rollbackRevisionId()));
    }
    @Override public List<UUID> monitorableRevisionIds(Instant now, int limit) { return List.of(); }
    @Override public List<MonitorRecord> monitorsForRobot(UUID workspaceId, UUID robotId, int limit) {
        return monitors.values().stream().filter(m -> m.robotId().equals(robotId)).limit(limit).toList();
    }
    @Override public List<CohortRow> postChangeRows(UUID workspaceId, UUID robotId, UUID personaId, Window window, Metric metric,
            Instant epochStart, Instant cohortEnd, Instant now) {
        cohortQueries++; lastWindow = window; lastCohortEnd = cohortEnd; lastEpochStart = epochStart; lastPersona = personaId;
        return cohort.apply(window);
    }
}
