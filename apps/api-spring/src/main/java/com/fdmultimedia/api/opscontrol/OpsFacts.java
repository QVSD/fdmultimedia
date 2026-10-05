package com.fdmultimedia.api.opscontrol;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Raw authoritative facts read by the operations control plane; rules turn them into statuses and incidents. */
public final class OpsFacts {
    private OpsFacts() {}

    public record SchedulerInstance(String scheduler, String instanceId, String state, Instant lastStartedAt, Instant lastCompletedAt,
            Instant lastSucceededAt, Instant lastFailedAt, Long lastDurationMs, long processedCount, long resultCount,
            String lastFailureCode, Instant updatedAt) {}

    public record WorkerFact(UUID id, String name, Instant lastSeenAt, int activeJobs, int maxActiveJobs, List<String> capabilities,
            String agentVersion, UUID currentJobId, Instant lastCompletedAt, Instant lastFailedAt) {}

    public record JobFacts(long queued, long assigned, long running, long leaseExpired, long failedRecent, long succeededRecent,
            long failedBurst, Instant oldestQueuedAt) {}

    public record PublishingFacts(long scheduledDue, long scheduledOverdue, long publishing, long publishedRecent, long failedRecent,
            long failedBurst, long outcomeUnknown, Instant oldestDueAt) {}

    public record ProviderFact(String provider, Instant lastSuccessAt, Instant lastFailureAt, long failuresSinceLastSuccess) {}

    public record AutomationFacts(long autoProposeRobots, long pendingProposals, long activeAuthorizations, long guardrailBlocked,
            long activeSafetyObservations, long openRollbackRecommendations, long memorySuppressedTransitions) {}

    public record RollbackFact(UUID id, Instant createdAt, String status) {}
}
