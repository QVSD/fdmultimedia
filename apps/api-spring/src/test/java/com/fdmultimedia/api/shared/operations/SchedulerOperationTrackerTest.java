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
        assertThat(tracker.snapshots()).hasSize(11);
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

    private static SchedulerOperationTracker withSink(Clock clock, SchedulerStatusSink sink) {
        org.springframework.beans.factory.ObjectProvider<SchedulerStatusSink> provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(sink);
        return new SchedulerOperationTracker(clock, provider, new ApiInstanceIdentity());
    }

    @Test
    void completionIsPublishedToTheSharedSinkWithTheInstanceIdentity() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        SchedulerOperationTracker tracker = withSink(clock, (name, instance, snapshot) -> seen.add(name + ":" + snapshot.state()));
        tracker.run(SchedulerOperationTracker.ADAPTIVE_MEMORY, () -> new SchedulerOperationTracker.Outcome(1, 1));
        assertThat(seen).containsExactly("adaptive-memory:RUNNING", "adaptive-memory:SUCCEEDED");
    }

    @Test
    void fastSchedulersPublishOnlyOnCompletion() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        SchedulerOperationTracker tracker = withSink(clock, (name, instance, snapshot) -> seen.add(snapshot.state()));
        tracker.run(SchedulerOperationTracker.PUBLISH_SCHEDULE, () -> SchedulerOperationTracker.Outcome.NONE);
        assertThat(seen).containsExactly("SUCCEEDED");
    }

    @Test
    void failuresArePublishedAndStillPropagate() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        SchedulerOperationTracker tracker = withSink(clock, (name, instance, snapshot) -> seen.add(snapshot.state() + ":" + snapshot.lastFailureCode()));
        assertThatThrownBy(() -> tracker.run(SchedulerOperationTracker.POST_CHANGE_SAFETY, () -> { throw new IllegalStateException("x"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(seen).last().isEqualTo("FAILED:IllegalStateException");
    }

    @Test
    void aBrokenSinkNeverBreaksTheScheduler() {
        SchedulerOperationTracker tracker = withSink(clock, (name, instance, snapshot) -> { throw new IllegalStateException("db down"); });
        AtomicBoolean ran = new AtomicBoolean();
        tracker.run(SchedulerOperationTracker.ADAPTIVE_MEMORY, () -> { ran.set(true); return SchedulerOperationTracker.Outcome.NONE; });
        assertThat(ran).isTrue();
        assertThat(tracker.snapshots().stream().filter(r -> r.name().equals(SchedulerOperationTracker.ADAPTIVE_MEMORY)).findFirst().orElseThrow().state())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void inventoryNamesAreUnique() {
        assertThat(new SchedulerOperationTracker(clock).snapshots().stream().map(SchedulerOperationTracker.Snapshot::name).distinct().count()).isEqualTo(11);
    }
}
