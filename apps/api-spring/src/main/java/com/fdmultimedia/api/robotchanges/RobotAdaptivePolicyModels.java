package com.fdmultimedia.api.robotchanges;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RobotAdaptivePolicyModels {
    private RobotAdaptivePolicyModels() {}

    public enum ProposalAutomationMode { MANUAL_ONLY, AUTO_PROPOSE }

    public enum Trigger { CREATE, APPROVE, APPLY, CHECK }
    public enum Reason {
        POLICY_DISABLED,
        CHANGE_BUDGET_EXHAUSTED,
        COOLDOWN_ACTIVE,
        ACTIVE_EXPERIMENT,
        PENDING_CHANGE_EXISTS,
        POST_CHANGE_OBSERVATION_REQUIRED
    }

    public record UpdateRequest(Integer expectedRevision, Boolean enabled, Integer maxAppliedChangesPerWindow,
            Integer changeBudgetWindowDays, Integer cooldownHours, Boolean requireNoActiveExperiment,
            Boolean requireNoPendingChange, Boolean requirePostChangeObservation,
            ProposalAutomationMode proposalAutomationMode) {
        public UpdateRequest(Integer expectedRevision, Boolean enabled, Integer maxAppliedChangesPerWindow,
                Integer changeBudgetWindowDays, Integer cooldownHours, Boolean requireNoActiveExperiment,
                Boolean requireNoPendingChange, Boolean requirePostChangeObservation) {
            this(expectedRevision, enabled, maxAppliedChangesPerWindow, changeBudgetWindowDays, cooldownHours,
                    requireNoActiveExperiment, requireNoPendingChange, requirePostChangeObservation, null);
        }
    }

    public record PolicySummary(UUID robotId, int revision, boolean persisted, boolean enabled,
            int maxAppliedChangesPerWindow, int changeBudgetWindowDays, int cooldownHours,
            boolean requireNoActiveExperiment, boolean requireNoPendingChange,
            boolean requirePostChangeObservation, ProposalAutomationMode proposalAutomationMode, Instant updatedAt) {
        public PolicySummary(UUID robotId, int revision, boolean persisted, boolean enabled,
                int maxAppliedChangesPerWindow, int changeBudgetWindowDays, int cooldownHours,
                boolean requireNoActiveExperiment, boolean requireNoPendingChange,
                boolean requirePostChangeObservation, Instant updatedAt) {
            this(robotId, revision, persisted, enabled, maxAppliedChangesPerWindow, changeBudgetWindowDays,
                    cooldownHours, requireNoActiveExperiment, requireNoPendingChange,
                    requirePostChangeObservation, ProposalAutomationMode.MANUAL_ONLY, updatedAt);
        }
    }

    public record PolicyRevisionSummary(UUID id, UUID robotId, int revision, String previousValues,
            String newValues, UUID actorUserId, Instant createdAt) {}

    public record EvaluationSummary(UUID id, UUID proposalId, UUID robotId, String engineVersion,
            Trigger trigger, int policyRevision, boolean eligible, List<Reason> reasons,
            int budgetAllowed, int budgetUsed, int budgetRemaining, int budgetWindowDays,
            UUID latestConfigurationRevisionId, Instant lastConfigurationChangeAt,
            int cooldownHours, Instant cooldownEndsAt, UUID activeExperimentId, int pendingProposalCount,
            int runsSinceRevision, int publicationsSinceRevision, int eligibleByAgeCount,
            int analyticsPublicationCount, int metricSampleCount, BigDecimal coverage,
            int requiredSampleCount, BigDecimal requiredCoverage, String observationWindow, Instant evaluatedAt) {}

    public record Observation(int runs, int publications, int eligibleByAge, int analyticsPublications,
            int metricSamples, BigDecimal coverage) {
        static Observation empty() { return new Observation(0, 0, 0, 0, 0, null); }
    }
}
