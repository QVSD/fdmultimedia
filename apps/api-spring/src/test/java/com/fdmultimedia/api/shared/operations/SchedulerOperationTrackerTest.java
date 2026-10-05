package com.fdmultimedia.api.shared.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SchedulerOperationTrackerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void recordsBoundedSuccessCountsAndCompleteInventory() {
        SchedulerOperationTracker tracker = new SchedulerOperationTracker(clock);
        tracker.run(SchedulerOperationTracker.ADAPTIVE_MEMORY,
                () -> new SchedulerOperationTracker.Outcome(3, 2));

        var status = tracker.snapshots().stream()
                .filter(row -> row.name().equals(SchedulerOperationTracker.ADAPTIVE_MEMORY))
                .findFirst().orElseThrow();
        assertThat(status.state()).isEqualTo("SUCCEEDED");
        assertThat(status.processedCount()).isEqualTo(3);
        assertThat(status.resultCount()).isEqualTo(2);
        assertThat(status.lastDurationMs()).isZero();
        assertThat(tracker.snapshots()).hasSize(10);
    }

    @Test
    void recordsFailureAndAllowsLaterExecution() {
        SchedulerOperationTracker tracker = new SchedulerOperationTracker(clock);
        assertThatThrownBy(() -> tracker.run(SchedulerOperationTracker.POST_CHANGE_SAFETY,
                () -> { throw new IllegalStateException("bounded"); }))
                .isInstanceOf(IllegalStateException.class);
        tracker.run(SchedulerOperationTracker.POST_CHANGE_SAFETY,
                () -> new SchedulerOperationTracker.Outcome(1, 1));

        var status = tracker.snapshots().stream()
                .filter(row -> row.name().equals(SchedulerOperationTracker.POST_CHANGE_SAFETY))
                .findFirst().orElseThrow();
        assertThat(status.state()).isEqualTo("SUCCEEDED");
        assertThat(status.lastFailedAt()).isNotNull();
        assertThat(status.lastSucceededAt()).isNotNull();
    }

    @Test
    void shutdownPreventsNewBatches() {
        SchedulerOperationTracker tracker = new SchedulerOperationTracker(clock);
        tracker.stopAccepting();
        AtomicBoolean called = new AtomicBoolean();
        tracker.run(SchedulerOperationTracker.ROBOT_AUTOMATION,
                () -> { called.set(true); return SchedulerOperationTracker.Outcome.NONE; });

        assertThat(called).isFalse();
        assertThat(tracker.isAccepting()).isFalse();
    }
}
