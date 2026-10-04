package com.fdmultimedia.api.adaptivememory;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure projection of ordered immutable facts into directional (Robot, from, to) memory rows and the screening decision.
 * No persistence, clock, analytics or provider dependency: the same history always yields the same projection.
 */
public final class AdaptiveMemoryProjector {
    private AdaptiveMemoryProjector() {}

    public record Key(UUID robotId, UUID fromPersonaId, UUID toPersonaId) {}

    /** Deterministic source order: timestamp, then source type, source id and event type as stable tie-breaks. */
    public static final Comparator<Fact> ORDER = Comparator.comparing(Fact::occurredAt)
            .thenComparing(f -> f.sourceType().name()).thenComparing(Fact::sourceId).thenComparing(f -> f.type().name());

    public static Map<Key, Memory> project(List<Fact> facts) {
        List<Fact> ordered = new ArrayList<>(facts);
        ordered.sort(ORDER);
        Map<Key, Acc> accumulators = new LinkedHashMap<>();
        for (Fact f : ordered) {
            if (f.fromPersonaId() == null || f.toPersonaId() == null || f.fromPersonaId().equals(f.toPersonaId())) continue;
            accumulators.computeIfAbsent(new Key(f.robotId(), f.fromPersonaId(), f.toPersonaId()), k -> new Acc(f)).apply(f);
        }
        Map<Key, Memory> result = new LinkedHashMap<>();
        accumulators.forEach((k, a) -> result.put(k, a.memory()));
        return result;
    }

    private static final class Acc {
        final UUID workspaceId, robotId, from, to;
        Outcome latest;
        int proposals, applies, rollbacks, regressions, events;
        Instant first, last, proposed, rejected, applied, regression, rolledBack, evidence;
        UUID revisionId, evaluationId, recommendationId, experimentId;
        String safetyStatus;

        Acc(Fact f) { workspaceId = f.workspaceId(); robotId = f.robotId(); from = f.fromPersonaId(); to = f.toPersonaId(); }

        void apply(Fact f) {
            events++;
            if (first == null) first = f.occurredAt();
            last = f.occurredAt();
            if (f.revisionId() != null) revisionId = f.revisionId();
            if (f.experimentId() != null) experimentId = f.experimentId();
            switch (f.type()) {
                case PROPOSAL_CREATED -> { proposals++; proposed = f.occurredAt(); latest = Outcome.PROPOSED; evidence = f.occurredAt(); }
                case CHANGE_PROPOSAL_CREATED -> { latest = Outcome.PROPOSED; evidence = f.occurredAt(); }
                case PROPOSAL_REJECTED -> { rejected = f.occurredAt(); latest = Outcome.HUMAN_REJECTED; evidence = f.occurredAt(); }
                case CHANGE_PROPOSAL_APPROVED -> { latest = Outcome.APPROVED_NOT_APPLIED; evidence = f.occurredAt(); }
                case CHANGE_APPLIED -> { applies++; applied = f.occurredAt(); latest = Outcome.APPLIED; evidence = f.occurredAt(); safetyStatus = null; }
                case SAFETY_STABLE -> {
                    evaluationId = f.evaluationId(); safetyStatus = "READY_STABLE"; evidence = f.occurredAt();
                    // Stability can only describe an applied epoch; it never overrides an observed regression or a rollback.
                    if (latest == Outcome.APPLIED) latest = Outcome.OBSERVED_STABLE;
                }
                case SAFETY_REGRESSION -> {
                    regressions++; regression = f.occurredAt(); evaluationId = f.evaluationId(); safetyStatus = "READY_REGRESSION_OBSERVED";
                    evidence = f.occurredAt();
                    if (latest == Outcome.APPLIED || latest == Outcome.OBSERVED_STABLE) latest = Outcome.OBSERVED_REGRESSION;
                }
                // Recommendation lifecycle facts are recorded but never change the outcome: dismissal is not stability.
                case ROLLBACK_RECOMMENDED, ROLLBACK_DISMISSED -> { recommendationId = f.recommendationId(); evidence = f.occurredAt(); }
                case CHANGE_ROLLED_BACK -> { rollbacks++; rolledBack = f.occurredAt(); latest = Outcome.ROLLED_BACK; evidence = f.occurredAt(); }
                case TRANSITION_SUPERSEDED -> {
                    evidence = f.occurredAt();
                    // Only an applied-and-never-evaluated epoch becomes SUPERSEDED; observed stability/regression/rollback stay.
                    if (latest == Outcome.APPLIED) latest = Outcome.SUPERSEDED;
                }
            }
        }

        Memory memory() {
            return new Memory(workspaceId, robotId, from, to, latest, proposals, applies, rollbacks, regressions, first, last, proposed,
                    rejected, applied, regression, rolledBack, evidence, revisionId, evaluationId, recommendationId, experimentId,
                    safetyStatus, events);
        }
    }

    /** Screening: all currently applicable reasons in declaration order, {@code suppressionUntil = max(active expiries)}. */
    public static Decision decide(UUID robotId, UUID robotPersonaId, UUID fromPersonaId, UUID toPersonaId, Memory memory, Instant now) {
        List<SuppressionReason> reasons = new ArrayList<>();
        Instant until = null;
        if (toPersonaId.equals(robotPersonaId)) reasons.add(SuppressionReason.CURRENTLY_ACTIVE);
        if (memory != null) {
            until = consider(reasons, until, SuppressionReason.RECENTLY_PROPOSED, memory.lastProposedAt(), AdaptiveMemoryModels.PROPOSAL_SUPPRESSION, now);
            until = consider(reasons, until, SuppressionReason.HUMAN_REJECTED, memory.lastRejectedAt(), AdaptiveMemoryModels.REJECTION_SUPPRESSION, now);
            until = consider(reasons, until, SuppressionReason.RECENTLY_APPLIED, memory.lastAppliedAt(), AdaptiveMemoryModels.APPLIED_SUPPRESSION, now);
            until = consider(reasons, until, SuppressionReason.OBSERVED_REGRESSION, memory.lastRegressionAt(), AdaptiveMemoryModels.REGRESSION_SUPPRESSION, now);
            until = consider(reasons, until, SuppressionReason.ROLLED_BACK, memory.lastRolledBackAt(), AdaptiveMemoryModels.ROLLBACK_SUPPRESSION, now);
        }
        // CURRENTLY_ACTIVE (reported first by declaration order elsewhere) has no expiry; keep the enum order stable.
        reasons.sort(Comparator.comparingInt(Enum::ordinal));
        return new Decision(robotId, fromPersonaId, toPersonaId, reasons.isEmpty(), List.copyOf(reasons), until,
                memory == null ? null : memory.latestOutcome(), memory == null ? null : memory.latestEvidenceAt(),
                memory == null ? null : memory.latestRevisionId(), memory == null ? null : memory.latestEvaluationId(),
                memory == null ? null : memory.latestRecommendationId(), memory == null ? null : memory.latestExperimentId(),
                AdaptiveMemoryModels.SCREENING_ENGINE_VERSION);
    }

    /** Suppressed while {@code now < source + duration}; at the exact boundary the transition is eligible for normal screening. */
    private static Instant consider(List<SuppressionReason> reasons, Instant until, SuppressionReason reason, Instant source,
            java.time.Duration duration, Instant now) {
        if (source == null) return until;
        Instant expiry = source.plus(duration);
        if (!now.isBefore(expiry)) return until;
        reasons.add(reason);
        return until == null || expiry.isAfter(until) ? expiry : until;
    }
}
