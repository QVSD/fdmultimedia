package com.fdmultimedia.api.opscontrol;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 17Q value types. The operations control plane is a bounded, deterministic, read-mostly application view built from
 * authoritative platform state. It exposes statuses, counts, bounded identifiers and sanitized messages only.
 */
public final class OpsModels {
    private OpsModels() {}

    public static final String OVERVIEW_ENGINE = "OPERATIONS_OVERVIEW_V1";
    public static final String INCIDENT_ENGINE = "OPERATIONS_INCIDENTS_V1";

    public enum OverallStatus { HEALTHY, DEGRADED, ACTION_REQUIRED }
    public enum ComponentStatus { HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN }
    public enum Severity { INFO, WARNING, CRITICAL }
    public enum IncidentStatus { ACTIVE, RESOLVED }
    public enum WorkerState { ONLINE, STALE, OFFLINE }
    public enum SchedulerState { IDLE, RUNNING, DEGRADED, FAILED, UNKNOWN }

    // ---- dependencies / API ----

    public record Dependency(String name, ComponentStatus status, String detail, boolean configured, Instant observedAt,
            long ageSeconds, boolean cached, Long probeMillis) {}

    public record ApiSection(ComponentStatus status, String instance, long uptimeSeconds, String liveness, String readiness) {}

    // ---- workers ----

    public record WorkerRow(UUID id, String name, WorkerState state, Instant lastHeartbeatAt, int activeJobs, int maxActiveJobs,
            List<String> capabilities, String agentVersion, UUID currentJobId, Instant lastCompletedAt, Instant lastFailedAt) {}

    public record WorkersSection(ComponentStatus status, long total, long online, long stale, long offline, long recentlySeenOffline,
            long staleAfterSeconds, long offlineAfterSeconds) {}

    // ---- jobs ----

    public record JobsSection(ComponentStatus status, long queued, long assigned, long running, long leaseExpired,
            long failedRecent, long succeededRecent, Long oldestQueuedAgeSeconds, Severity backlogSeverity,
            long backlogWarningSeconds, long backlogCriticalSeconds, long recentWindowHours) {}

    /** retryState: NONE, RETRY_PENDING (queued after a failed attempt) or EXHAUSTED (failed with no attempts left). */
    public record JobRow(UUID id, String type, String status, Instant queuedAt, Instant finishedAt, int attemptCount,
            int maxAttempts, String retryState, String failureCategory, String failureMessage, Instant leaseExpiresAt) {}

    public record AttemptRow(int jobAttempt, String outcome, String errorCategory, Instant startedAt, Instant finishedAt) {}

    public record JobDetail(JobRow job, String workerName, Instant assignedAt, Instant startedAt, List<AttemptRow> publishingAttempts) {}

    // ---- schedulers ----

    public record SchedulerRow(String name, boolean enabled, SchedulerState state, boolean stale, long cadenceSeconds, long staleAfterSeconds,
            Instant lastStartedAt, Instant lastCompletedAt, Instant lastSucceededAt, Instant lastFailedAt, Long lastDurationMs,
            long processedCount, long resultCount, String lastFailureCode, int reportingInstances, int batchBound,
            String classification) {}

    public record SchedulersSection(ComponentStatus status, int total, int idle, int running, int degraded, int failed, int unknown) {}

    // ---- publishing ----

    public record PublishingSection(ComponentStatus status, long scheduledDue, long scheduledOverdue, long publishing,
            long publishedRecent, long failedRecent, long outcomeUnknown, Long oldestOverdueAgeSeconds,
            long overdueWarningSeconds, long overdueCriticalSeconds, long recentWindowHours) {}

    public record PublicationRow(UUID id, String provider, String status, Instant createdAt, Instant finishedAt, int attempts,
            String failureCategory, boolean outcomeUnknown, String retryGuidance) {}

    public record ProviderRow(String provider, ComponentStatus status, Instant lastSuccessAt, Instant lastFailureAt,
            long failuresSinceLastSuccess) {}

    // ---- automation ----

    public record AutomationSection(ComponentStatus status, long autoProposeRobots, long pendingProposals, long activeAuthorizations,
            long guardrailBlocked, long activeSafetyObservations, long openRollbackRecommendations, long memorySuppressedTransitions) {}

    // ---- incidents ----

    public record Incident(UUID id, String key, Severity severity, IncidentStatus status, String category, String title,
            String conditionCode, String detail, String suggestedAction, String subjectType, UUID subjectId,
            Instant firstObservedAt, Instant lastObservedAt, Instant resolvedAt, Instant acknowledgedAt, boolean acknowledged,
            boolean persisted) {}

    public record IncidentsSection(ComponentStatus status, long active, long critical, long warning, long info, long unacknowledged) {}

    public record DerivedIncident(String key, Severity severity, String category, String title, String conditionCode,
            String detail, String suggestedAction, String subjectType, UUID subjectId) {}

    // ---- overview ----

    public record Overview(String engineVersion, Instant observedAt, OverallStatus overallStatus, ApiSection api,
            List<Dependency> dependencies, WorkersSection workers, JobsSection jobs, SchedulersSection schedulers,
            PublishingSection publishing, AutomationSection automation, IncidentsSection incidents,
            List<Incident> topIncidents, long refreshHintSeconds) {}

    public record Page<T>(List<T> items, int page, int size, long total) {}

    public record Thresholds(Duration backlogWarning, Duration backlogCritical, Duration workerStaleAfter, Duration workerOfflineAfter,
            Duration overdueWarning, Duration overdueCritical, Duration recentWindow, int failureBurst) {}
}
