package com.fdmultimedia.api.safety;

import com.fdmultimedia.api.analytics.DashboardQuery.Metric;
import com.fdmultimedia.api.analytics.DashboardQuery.Window;
import com.fdmultimedia.api.safety.PostChangeSafetyModels.*;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for Phase 17M. All reads/writes are workspace-scoped; implemented with JDBC (see JdbcSafetyStore). */
public interface SafetyStore {
    /** Transaction-scoped advisory lock that serializes every writer of one configuration revision's safety state. */
    void lockRevision(UUID revisionId);

    Optional<MonitorRecord> findMonitor(UUID workspaceId, UUID revisionId);

    void insertMonitor(MonitorRecord monitor, Instant now);

    void touchMonitor(UUID monitorId, Instant now);

    void closeMonitor(UUID monitorId, MonitorStatus status, CompletedReason reason, Instant epochEnd, Instant now);

    Optional<BaselineRecord> findBaseline(UUID monitorId, Window window);

    List<BaselineRecord> baselines(UUID monitorId);

    void insertBaseline(BaselineRecord baseline);

    Optional<EvaluationRecord> findEvaluationByFingerprint(UUID monitorId, Window window, String fingerprint);

    int nextEvaluationRevision(UUID monitorId, Window window);

    void insertEvaluation(EvaluationRecord evaluation);

    List<EvaluationRecord> listEvaluations(UUID workspaceId, UUID revisionId, int limit);

    List<EvaluationRecord> latestEvaluations(UUID monitorId);

    Optional<EvaluationRecord> getEvaluation(UUID workspaceId, UUID id);

    Optional<RecommendationRecord> findActiveRecommendation(UUID revisionId);

    Optional<RecommendationRecord> findRecommendationForWindow(UUID revisionId, Window window);

    Optional<RecommendationRecord> findLatestRecommendation(UUID revisionId);

    void insertRecommendation(RecommendationRecord recommendation);

    Optional<RecommendationRecord> lockRecommendation(UUID workspaceId, UUID id);

    Optional<RecommendationRecord> getRecommendation(UUID workspaceId, UUID id);

    List<RecommendationRecord> listRecommendations(UUID workspaceId, Collection<RecommendationStatus> statuses, UUID robotId, int limit);

    void updateRecommendation(UUID id, RecommendationStatus status, Instant now, UUID userId, UUID rollbackRevisionId);

    /** Bounded list of (revisionId) still needing evaluation: no monitor yet, or monitor still MONITORING. */
    List<UUID> monitorableRevisionIds(Instant now, int limit);

    /** Forward-revision monitors of a Robot, most recent revision first. */
    List<MonitorRecord> monitorsForRobot(UUID workspaceId, UUID robotId, int limit);

    /** One row per RobotRun of the epoch (bounded), with the earliest published Publication and the canonical snapshot. */
    List<CohortRow> postChangeRows(UUID workspaceId, UUID robotId, UUID personaId, Window window, Metric metric,
            Instant epochStart, Instant cohortEnd, Instant now);
}
