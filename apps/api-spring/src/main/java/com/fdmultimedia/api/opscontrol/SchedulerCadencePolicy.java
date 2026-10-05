package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The configured cadence and enabled flag of every tracked scheduler, resolved from the same properties (and defaults) the
 * schedulers themselves use, so staleness is cadence-aware: an hourly scheduler is never "late" after five minutes.
 * A cadence of {@link Duration#ZERO} marks an on-demand / framework-managed operation that has no expected tick.
 */
@Component
public class SchedulerCadencePolicy {
    private record Spec(String intervalProperty, long defaultMillis, String enabledProperty, boolean enabledDefault) {}

    private static final Map<String, Spec> SPECS = Map.of(
            SchedulerOperationTracker.PUBLISH_SCHEDULE, new Spec("app.publishing.schedule.poll-interval-ms", 15_000, "app.publishing.schedule.enabled", true),
            SchedulerOperationTracker.PUBLICATION_ANALYTICS, new Spec("app.publication-analytics.poll-interval-ms", 60_000, "app.publication-analytics.enabled", true),
            SchedulerOperationTracker.ROBOT_AUTOMATION, new Spec("app.robots.poll-interval-ms", 15_000, "app.robots.automation-enabled", true),
            SchedulerOperationTracker.AUTONOMOUS_PROPOSALS, new Spec("app.autonomous-proposals.reconcile-interval-ms", 3_600_000, null, true),
            SchedulerOperationTracker.ADAPTIVE_EXECUTION, new Spec("app.adaptive-execution.reconcile-interval-ms", 900_000, null, true),
            SchedulerOperationTracker.POST_CHANGE_SAFETY, new Spec("app.post-change-safety.reconcile-interval-ms", 3_600_000, null, true),
            SchedulerOperationTracker.ADAPTIVE_MEMORY, new Spec("app.adaptive-memory.reconcile-interval-ms", 3_600_000, null, true),
            SchedulerOperationTracker.OPERATIONS_INCIDENTS, new Spec("app.operations.incident-interval-ms", 30_000, null, true));

    /** The retention job is a daily cron. */
    private static final Duration DAILY = Duration.ofDays(1);

    private final Environment environment;

    public SchedulerCadencePolicy(Environment environment) { this.environment = environment; }

    public Duration cadence(String scheduler) {
        if (SchedulerOperationTracker.SCHEDULING_RETENTION.equals(scheduler)) return DAILY;
        Spec spec = SPECS.get(scheduler);
        if (spec == null) return Duration.ZERO;
        Long configured = environment.getProperty(spec.intervalProperty(), Long.class);
        return Duration.ofMillis(Optional.ofNullable(configured).filter(v -> v > 0).orElse(spec.defaultMillis()));
    }

    public boolean enabled(String scheduler) {
        Spec spec = SPECS.get(scheduler);
        if (spec == null || spec.enabledProperty() == null) return true;
        return environment.getProperty(spec.enabledProperty(), Boolean.class, spec.enabledDefault());
    }
}
