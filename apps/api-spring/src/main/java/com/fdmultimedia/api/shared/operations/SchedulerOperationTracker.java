package com.fdmultimedia.api.shared.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SchedulerOperationTracker {
    public static final String PUBLISH_SCHEDULE = "publish-schedule-dispatch";
    public static final String PUBLICATION_ANALYTICS = "publication-analytics";
    public static final String ROBOT_AUTOMATION = "robot-automation";
    public static final String AUTONOMOUS_PROPOSALS = "autonomous-proposals";
    public static final String ADAPTIVE_EXECUTION = "adaptive-execution";
    public static final String POST_CHANGE_SAFETY = "post-change-safety";
    public static final String ADAPTIVE_MEMORY = "adaptive-memory";
    public static final String SCHEDULING_RETENTION = "scheduling-decision-retention";
    public static final String OPERATIONS_INCIDENTS = "operations-incidents";

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(PUBLISH_SCHEDULE, "A", 50, "FOR UPDATE SKIP LOCKED; one schedule per transaction"),
            new Definition(PUBLICATION_ANALYTICS, "A", 100, "claim token and lease; unique publication/bucket"),
            new Definition(ROBOT_AUTOMATION, "A", 60, "configured dispatch/reconciliation limits; row locks and idempotent runs"),
            new Definition(AUTONOMOUS_PROPOSALS, "A", 100, "policy row lock and unique opportunity fingerprint"),
            new Definition(ADAPTIVE_EXECUTION, "A", 100, "authorization row lock and unique logical attempt"),
            new Definition(POST_CHANGE_SAFETY, "A", 100, "per-revision advisory lock and unique recommendation"),
            new Definition(ADAPTIVE_MEMORY, "A", 100, "per-Robot advisory lock and unique source event"),
            new Definition(SCHEDULING_RETENTION, "A", 10_000, "bounded idempotent delete"),
            new Definition(OPERATIONS_INCIDENTS, "A", 500, "advisory single-flight; unique active incident per workspace and key"),
            new Definition("job-lease-recovery", "A", 0, "on-demand during claim; row locks with SKIP LOCKED"),
            new Definition("spring-session-cleanup", "A", 0, "framework-managed conditional expiry delete"));

    /** Sub-minute schedulers publish only on completion so their rows are not rewritten twice per tick. */
    private static final Set<String> FAST = Set.of(PUBLISH_SCHEDULE, PUBLICATION_ANALYTICS, ROBOT_AUTOMATION, OPERATIONS_INCIDENTS);
    private static final Logger log = LoggerFactory.getLogger(SchedulerOperationTracker.class);

    private final Clock clock;
    private final ObjectProvider<SchedulerStatusSink> sinkProvider;
    private final ApiInstanceIdentity identity;
    private final Map<String, MutableStatus> statuses = new ConcurrentHashMap<>();
    private volatile boolean accepting = true;

    public SchedulerOperationTracker(Clock clock) {
        this(clock, null, null);
    }

    @Autowired
    public SchedulerOperationTracker(Clock clock, ObjectProvider<SchedulerStatusSink> sinkProvider, ApiInstanceIdentity identity) {
        this.clock = clock;
        this.sinkProvider = sinkProvider;
        this.identity = identity;
        DEFINITIONS.forEach(definition -> statuses.put(definition.name(), new MutableStatus(definition)));
    }

    /** Publishes this instance's latest status to the shared sink. Observability must never break a scheduler. */
    private void publish(String name, MutableStatus status) {
        if (sinkProvider == null || identity == null) return;
        try {
            SchedulerStatusSink sink = sinkProvider.getIfAvailable();
            if (sink != null) sink.record(name, identity.value(), status.snapshot());
        } catch (RuntimeException ex) {
            log.debug("Scheduler status publication skipped type={}", ex.getClass().getSimpleName());
        }
    }

    public void run(String name, Supplier<Outcome> operation) {
        if (!accepting) return;
        MutableStatus status = require(name);
        Instant started = Instant.now(clock);
        status.started(started);
        if (!FAST.contains(name)) publish(name, status);
        try {
            Outcome outcome = operation.get();
            status.succeeded(started, Instant.now(clock), outcome == null ? Outcome.NONE : outcome);
            publish(name, status);
        } catch (RuntimeException | Error failure) {
            status.failed(started, Instant.now(clock), failure.getClass().getSimpleName());
            publish(name, status);
            throw failure;
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stopAccepting() {
        accepting = false;
    }

    public boolean isAccepting() {
        return accepting;
    }

    public List<Snapshot> snapshots() {
        return DEFINITIONS.stream().map(definition -> require(definition.name()).snapshot()).toList();
    }

    private MutableStatus require(String name) {
        MutableStatus status = statuses.get(name);
        if (status == null) throw new IllegalArgumentException("Unknown scheduler operation");
        return status;
    }

    public record Definition(String name, String classification, int batchBound, String safetyMechanism) {}
    public record Outcome(long processedCount, long resultCount) {
        public static final Outcome NONE = new Outcome(0, 0);
    }
    public record Snapshot(String name, String classification, int batchBound, String safetyMechanism,
            Instant lastStartedAt, Instant lastCompletedAt, Instant lastSucceededAt, Instant lastFailedAt,
            Long lastDurationMs, long processedCount, long resultCount, String lastFailureCode, String state) {}

    private static final class MutableStatus {
        private final Definition definition;
        private Instant lastStartedAt;
        private Instant lastCompletedAt;
        private Instant lastSucceededAt;
        private Instant lastFailedAt;
        private Long lastDurationMs;
        private long processedCount;
        private long resultCount;
        private String lastFailureCode;
        // batchBound 0 marks framework-managed / on-demand operations whose execution is not timed by this tracker
        private String state;

        private MutableStatus(Definition definition) {
            this.definition = definition;
            this.state = definition.batchBound() == 0 ? "ON_DEMAND_NOT_TIMED" : "NEVER_RUN";
        }
        synchronized void started(Instant at) { lastStartedAt = at; state = "RUNNING"; }
        synchronized void succeeded(Instant started, Instant completed, Outcome outcome) {
            lastCompletedAt = completed; lastSucceededAt = completed;
            lastDurationMs = Math.max(0, Duration.between(started, completed).toMillis());
            processedCount = outcome.processedCount(); resultCount = outcome.resultCount();
            lastFailureCode = null; state = "SUCCEEDED";
        }
        synchronized void failed(Instant started, Instant completed, String code) {
            lastCompletedAt = completed; lastFailedAt = completed;
            lastDurationMs = Math.max(0, Duration.between(started, completed).toMillis());
            lastFailureCode = code; state = "FAILED";
        }
        synchronized Snapshot snapshot() {
            return new Snapshot(definition.name(), definition.classification(), definition.batchBound(),
                    definition.safetyMechanism(), lastStartedAt, lastCompletedAt, lastSucceededAt, lastFailedAt,
                    lastDurationMs, processedCount, resultCount, lastFailureCode, state);
        }
    }
}
