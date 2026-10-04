package com.fdmultimedia.api.adaptivememory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryModels.*;
import com.fdmultimedia.api.adaptivememory.AdaptiveMemoryProjector.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdaptiveMemoryProjectorTest {
    static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    static final UUID WS = UUID.randomUUID();
    final UUID robot1 = UUID.randomUUID();
    final UUID robot2 = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();
    int seq;

    Fact fact(UUID robot, UUID from, UUID to, EventType type, SourceType source, Instant at) {
        // deterministic ids so ordering tie-breaks are reproducible
        UUID id = new UUID(0, ++seq);
        return new Fact(type, source, id, at, WS, robot, from, to, id, null, id, id, "HUMAN_APPLY", "H72", null);
    }

    Fact f(EventType type, SourceType source, Instant at) { return fact(robot1, a, b, type, source, at); }

    Memory memoryOf(List<Fact> facts) { return AdaptiveMemoryProjector.project(facts).get(new Key(robot1, a, b)); }

    Decision decide(Memory m, Instant now) { return AdaptiveMemoryProjector.decide(robot1, a, a, b, m, now); }

    @Test
    void transitionIsDirectionalAndRobotScoped() {
        List<Fact> facts = new ArrayList<>(List.of(
                f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.CHANGE_ROLLED_BACK, SourceType.ROBOT_CONFIGURATION_REVISION, T0.plusSeconds(10)),
                fact(robot1, b, a, EventType.PROPOSAL_CREATED, SourceType.OPTIMIZATION_PROPOSAL, T0.plusSeconds(20)),
                fact(robot2, a, b, EventType.PROPOSAL_CREATED, SourceType.OPTIMIZATION_PROPOSAL, T0.plusSeconds(30))));
        Map<Key, Memory> projected = AdaptiveMemoryProjector.project(facts);
        assertThat(projected).containsOnlyKeys(new Key(robot1, a, b), new Key(robot1, b, a), new Key(robot2, a, b));
        assertThat(projected.get(new Key(robot1, a, b)).latestOutcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(projected.get(new Key(robot1, b, a)).latestOutcome()).isEqualTo(Outcome.PROPOSED);
        // another Robot's identical transition and the reverse direction are not suppressed by robot1's A->B rollback
        Instant now = T0.plus(Duration.ofDays(1));
        assertThat(decide(projected.get(new Key(robot1, a, b)), now).eligible()).isFalse();
        assertThat(AdaptiveMemoryProjector.decide(robot2, a, a, b, projected.get(new Key(robot2, a, b)), now).reasons())
                .doesNotContain(SuppressionReason.ROLLED_BACK);
        assertThat(AdaptiveMemoryProjector.decide(robot1, b, b, a, projected.get(new Key(robot1, b, a)), now).reasons())
                .doesNotContain(SuppressionReason.ROLLED_BACK);
    }

    @Test
    void proposalCreatesProposedMemoryAndSuppressesForThirtyDays() {
        Memory m = memoryOf(List.of(f(EventType.PROPOSAL_CREATED, SourceType.OPTIMIZATION_PROPOSAL, T0)));
        assertThat(m.latestOutcome()).isEqualTo(Outcome.PROPOSED);
        assertThat(m.proposalCount()).isEqualTo(1);
        Decision d = decide(m, T0.plus(Duration.ofDays(29)));
        assertThat(d.reasons()).containsExactly(SuppressionReason.RECENTLY_PROPOSED);
        assertThat(d.suppressionUntil()).isEqualTo(T0.plus(Duration.ofDays(30)));
    }

    @Test
    void humanRejectionSuppressesThirtyDaysWithExactBoundary() {
        Memory m = memoryOf(List.of(
                f(EventType.PROPOSAL_CREATED, SourceType.OPTIMIZATION_PROPOSAL, T0),
                f(EventType.PROPOSAL_REJECTED, SourceType.OPTIMIZATION_PROPOSAL, T0.plus(Duration.ofDays(1)))));
        assertThat(m.latestOutcome()).isEqualTo(Outcome.HUMAN_REJECTED);
        Instant expiry = T0.plus(Duration.ofDays(31));
        Decision before = decide(m, expiry.minusMillis(1));
        assertThat(before.reasons()).containsExactly(SuppressionReason.HUMAN_REJECTED);
        assertThat(before.eligible()).isFalse();
        Decision atBoundary = decide(m, expiry);
        assertThat(atBoundary.eligible()).as("suppressed while now < until; eligible for normal screening at the exact boundary").isTrue();
        assertThat(atBoundary.reasons()).isEmpty();
    }

    @Test
    void applyStableAndRegressionAreFactualOutcomes() {
        Memory applied = memoryOf(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0)));
        assertThat(applied.latestOutcome()).isEqualTo(Outcome.APPLIED);
        assertThat(applied.applyCount()).isEqualTo(1);
        Memory stable = memoryOf(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.SAFETY_STABLE, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plusSeconds(60))));
        assertThat(stable.latestOutcome()).isEqualTo(Outcome.OBSERVED_STABLE);
        assertThat(stable.lastRegressionAt()).isNull();
        assertThat(stable.latestSafetyStatus()).isEqualTo("READY_STABLE");
        Memory regression = memoryOf(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.SAFETY_REGRESSION, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plusSeconds(60))));
        assertThat(regression.latestOutcome()).isEqualTo(Outcome.OBSERVED_REGRESSION);
        Instant until = T0.plusSeconds(60).plus(Duration.ofDays(90));
        assertThat(decide(regression, until.minusMillis(1)).reasons()).contains(SuppressionReason.OBSERVED_REGRESSION);
        assertThat(decide(regression, until).reasons()).doesNotContain(SuppressionReason.OBSERVED_REGRESSION);
    }

    @Test
    void rollbackSuppressesNinetyDaysAndPreservesTheEarlierRegression() {
        Memory m = memoryOf(List.of(
                f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.SAFETY_REGRESSION, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plus(Duration.ofDays(4))),
                f(EventType.CHANGE_ROLLED_BACK, SourceType.ROBOT_CONFIGURATION_REVISION, T0.plus(Duration.ofDays(5)))));
        assertThat(m.latestOutcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(m.regressionCount()).as("the regression event is not erased by the later rollback").isEqualTo(1);
        assertThat(m.rollbackCount()).isEqualTo(1);
        Decision d = decide(m, T0.plus(Duration.ofDays(6)));
        assertThat(d.reasons()).contains(SuppressionReason.OBSERVED_REGRESSION, SuppressionReason.ROLLED_BACK);
        // max of active expiries: rollback (day 5 + 90) outlasts regression (day 4 + 90)
        assertThat(d.suppressionUntil()).isEqualTo(T0.plus(Duration.ofDays(95)));
    }

    @Test
    void dismissAndAcknowledgeNeverClearRegressionSuppression() {
        Memory m = memoryOf(List.of(
                f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.SAFETY_REGRESSION, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plusSeconds(60)),
                f(EventType.ROLLBACK_RECOMMENDED, SourceType.ROLLBACK_RECOMMENDATION, T0.plusSeconds(61)),
                f(EventType.ROLLBACK_DISMISSED, SourceType.ROLLBACK_RECOMMENDATION, T0.plusSeconds(120))));
        assertThat(m.latestOutcome()).isEqualTo(Outcome.OBSERVED_REGRESSION);
        assertThat(m.lastRegressionAt()).isNotNull();
        assertThat(decide(m, T0.plus(Duration.ofDays(10))).reasons()).contains(SuppressionReason.OBSERVED_REGRESSION);
        // acknowledgement is not even projected; with only a recommendation fact the outcome is unchanged as well
        Memory ack = memoryOf(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.SAFETY_REGRESSION, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plusSeconds(60)),
                f(EventType.ROLLBACK_RECOMMENDED, SourceType.ROLLBACK_RECOMMENDATION, T0.plusSeconds(61))));
        assertThat(ack.latestOutcome()).isEqualTo(Outcome.OBSERVED_REGRESSION);
    }

    @Test
    void supersedeDoesNotFakeStabilityOrEraseRegressionOrRollback() {
        Memory neverEvaluated = memoryOf(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.TRANSITION_SUPERSEDED, SourceType.ROBOT_CONFIGURATION_REVISION, T0.plusSeconds(60))));
        assertThat(neverEvaluated.latestOutcome()).isEqualTo(Outcome.SUPERSEDED);
        Memory regressed = memoryOf(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.SAFETY_REGRESSION, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plusSeconds(60)),
                f(EventType.TRANSITION_SUPERSEDED, SourceType.ROBOT_CONFIGURATION_REVISION, T0.plusSeconds(120))));
        assertThat(regressed.latestOutcome()).isEqualTo(Outcome.OBSERVED_REGRESSION);
        assertThat(regressed.lastRegressionAt()).isNotNull();
        Memory stable = memoryOf(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.SAFETY_STABLE, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plusSeconds(60)),
                f(EventType.TRANSITION_SUPERSEDED, SourceType.ROBOT_CONFIGURATION_REVISION, T0.plusSeconds(120))));
        assertThat(stable.latestOutcome()).isEqualTo(Outcome.OBSERVED_STABLE);
    }

    @Test
    void stableEvidenceNeverOverridesARegressionOrRollback() {
        Memory m = memoryOf(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.SAFETY_REGRESSION, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plusSeconds(60)),
                f(EventType.SAFETY_STABLE, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plusSeconds(120))));
        assertThat(m.latestOutcome()).isEqualTo(Outcome.OBSERVED_REGRESSION);
    }

    @Test
    void multipleReasonsAreDeterministicallyOrderedWithTheMaximumExpiry() {
        Memory m = memoryOf(List.of(
                f(EventType.PROPOSAL_CREATED, SourceType.OPTIMIZATION_PROPOSAL, T0),
                f(EventType.PROPOSAL_REJECTED, SourceType.OPTIMIZATION_PROPOSAL, T0.plus(Duration.ofDays(2))),
                f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0.plus(Duration.ofDays(3))),
                f(EventType.SAFETY_REGRESSION, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plus(Duration.ofDays(6))),
                f(EventType.CHANGE_ROLLED_BACK, SourceType.ROBOT_CONFIGURATION_REVISION, T0.plus(Duration.ofDays(7)))));
        Decision d = decide(m, T0.plus(Duration.ofDays(8)));
        assertThat(d.reasons()).containsExactly(SuppressionReason.RECENTLY_PROPOSED, SuppressionReason.HUMAN_REJECTED,
                SuppressionReason.RECENTLY_APPLIED, SuppressionReason.OBSERVED_REGRESSION, SuppressionReason.ROLLED_BACK);
        assertThat(d.suppressionUntil()).isEqualTo(T0.plus(Duration.ofDays(97)));
        assertThat(decide(m, T0.plus(Duration.ofDays(8))).reasons()).isEqualTo(d.reasons()); // stable
    }

    @Test
    void targetAlreadyActiveIsSuppressedWithoutExpiry() {
        Decision d = AdaptiveMemoryProjector.decide(robot1, b, a, b, null, T0);
        assertThat(d.eligible()).isFalse();
        assertThat(d.reasons()).containsExactly(SuppressionReason.CURRENTLY_ACTIVE);
        assertThat(d.suppressionUntil()).isNull();
    }

    @Test
    void noMemoryMeansEligibleAndLaterApplyRecentlyAppliedExpires() {
        assertThat(AdaptiveMemoryProjector.decide(robot1, a, a, b, null, T0).eligible()).isTrue();
        Memory m = memoryOf(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0)));
        assertThat(decide(m, T0.plus(Duration.ofDays(29))).reasons()).containsExactly(SuppressionReason.RECENTLY_APPLIED);
        assertThat(decide(m, T0.plus(Duration.ofDays(30))).eligible()).isTrue();
    }

    @Test
    void projectionIsDeterministicAndIndependentOfInputOrderIncludingEqualTimestamps() {
        List<Fact> facts = new ArrayList<>(List.of(
                f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                f(EventType.SAFETY_STABLE, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0),
                f(EventType.PROPOSAL_CREATED, SourceType.OPTIMIZATION_PROPOSAL, T0),
                f(EventType.CHANGE_ROLLED_BACK, SourceType.ROBOT_CONFIGURATION_REVISION, T0)));
        Map<Key, Memory> first = AdaptiveMemoryProjector.project(facts);
        for (int i = 0; i < 6; i++) {
            Collections.shuffle(facts, new Random(i));
            assertThat(AdaptiveMemoryProjector.project(facts)).isEqualTo(first);
        }
        assertThat(AdaptiveMemoryProjector.project(AdaptiveMemoryProjector.project(facts).isEmpty() ? List.of() : facts)).isEqualTo(first);
    }

    @Test
    void lateSafetyEvidenceUpdatesTheOutcomeWithoutTouchingUnrelatedHistory() {
        List<Fact> history = new ArrayList<>(List.of(f(EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                fact(robot1, a, c, EventType.PROPOSAL_CREATED, SourceType.OPTIMIZATION_PROPOSAL, T0.plusSeconds(5))));
        Map<Key, Memory> before = AdaptiveMemoryProjector.project(history);
        history.add(f(EventType.SAFETY_REGRESSION, SourceType.POST_CHANGE_SAFETY_EVALUATION, T0.plus(Duration.ofDays(4))));
        Map<Key, Memory> after = AdaptiveMemoryProjector.project(history);
        assertThat(after.get(new Key(robot1, a, b)).latestOutcome()).isEqualTo(Outcome.OBSERVED_REGRESSION);
        assertThat(after.get(new Key(robot1, a, c))).isEqualTo(before.get(new Key(robot1, a, c)));
    }

    @Test
    void invalidTransitionsAreIgnored() {
        assertThat(AdaptiveMemoryProjector.project(List.of(fact(robot1, a, a, EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0),
                fact(robot1, null, b, EventType.CHANGE_APPLIED, SourceType.ROBOT_CONFIGURATION_REVISION, T0)))).isEmpty();
    }

    @Test
    void outcomeIsAFactualEnumWithNoScoreOrRank() {
        assertThat(Outcome.values()).extracting(Enum::name).containsExactly("PROPOSED", "HUMAN_REJECTED", "APPROVED_NOT_APPLIED", "APPLIED",
                "OBSERVED_STABLE", "OBSERVED_REGRESSION", "ROLLED_BACK", "SUPERSEDED");
        assertThat(Arrays.stream(Memory.class.getRecordComponents()).map(rc -> rc.getName().toLowerCase()))
                .noneMatch(n -> n.contains("score") || n.contains("rank") || n.contains("weight") || n.contains("reward"));
    }
}
