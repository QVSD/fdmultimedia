package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.opscontrol.OpsFacts.*;
import com.fdmultimedia.api.opscontrol.OpsModels.*;
import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The deterministic core of the control plane: pure functions from facts and thresholds to statuses and incidents. No I/O, no clock, no
 * randomness, no language model. The same facts always produce the same statuses, incident keys, severities and suggested actions.
 */
public final class OpsRules {
    private OpsRules() {}

    public static final String CATEGORY_DEPENDENCY = "DEPENDENCY";
    public static final String CATEGORY_WORKERS = "WORKERS";
    public static final String CATEGORY_JOBS = "JOBS";
    public static final String CATEGORY_SCHEDULER = "SCHEDULER";
    public static final String CATEGORY_PUBLISHING = "PUBLISHING";
    public static final String CATEGORY_AUTOMATION = "AUTOMATION";

    /** Schedulers whose silence stops customer-visible behavior (publishing); their staleness is CRITICAL rather than WARNING. */
    private static final java.util.Set<String> CRITICAL_PATH_SCHEDULERS = java.util.Set.of(SchedulerOperationTracker.PUBLISH_SCHEDULE);

    // ------------------------------------------------------------------ workers

    public static WorkerState workerState(Instant lastSeenAt, Instant now, Duration heartbeatInterval, Duration offlineThreshold) {
        if (lastSeenAt == null) return WorkerState.OFFLINE;
        Duration age = Duration.between(lastSeenAt, now);
        if (age.compareTo(offlineThreshold) > 0) return WorkerState.OFFLINE;
        Duration staleAfter = heartbeatInterval.multipliedBy(2);
        return age.compareTo(staleAfter) > 0 && staleAfter.compareTo(offlineThreshold) < 0 ? WorkerState.STALE : WorkerState.ONLINE;
    }

    public static List<WorkerRow> workerRows(List<WorkerFact> facts, Instant now, Duration heartbeat, Duration offline) {
        return facts.stream().map(f -> new WorkerRow(f.id(), f.name(), workerState(f.lastSeenAt(), now, heartbeat, offline), f.lastSeenAt(),
                        f.activeJobs(), f.maxActiveJobs(), f.capabilities(), f.agentVersion(), f.currentJobId(), f.lastCompletedAt(), f.lastFailedAt()))
                .sorted(Comparator.comparing(WorkerRow::state).thenComparing(WorkerRow::name, String.CASE_INSENSITIVE_ORDER).thenComparing(WorkerRow::id))
                .toList();
    }

    public static WorkersSection workersSection(List<WorkerRow> rows, Instant now, Duration offlineWindow, Duration heartbeat, Duration offline) {
        long online = rows.stream().filter(r -> r.state() == WorkerState.ONLINE).count();
        long stale = rows.stream().filter(r -> r.state() == WorkerState.STALE).count();
        long offlineCount = rows.stream().filter(r -> r.state() == WorkerState.OFFLINE).count();
        long recentlyOffline = rows.stream().filter(r -> r.state() == WorkerState.OFFLINE && r.lastHeartbeatAt() != null
                && Duration.between(r.lastHeartbeatAt(), now).compareTo(offlineWindow) <= 0).count();
        ComponentStatus status;
        if (rows.isEmpty()) status = ComponentStatus.UNKNOWN;
        else if (online + stale == 0) status = ComponentStatus.UNAVAILABLE;
        else if (stale > 0 || recentlyOffline > 0) status = ComponentStatus.DEGRADED;
        else status = ComponentStatus.HEALTHY;
        return new WorkersSection(status, rows.size(), online, stale, offlineCount, recentlyOffline,
                heartbeat.multipliedBy(2).toSeconds(), offline.toSeconds());
    }

    // ------------------------------------------------------------------ jobs

    public static Severity backlogSeverity(Long oldestAgeSeconds, Duration warning, Duration critical) {
        if (oldestAgeSeconds == null) return null;
        if (oldestAgeSeconds > critical.toSeconds()) return Severity.CRITICAL;
        if (oldestAgeSeconds > warning.toSeconds()) return Severity.WARNING;
        return null;
    }

    public static Long ageSeconds(Instant at, Instant now) {
        return at == null ? null : Math.max(0, Duration.between(at, now).toSeconds());
    }

    public static JobsSection jobsSection(JobFacts f, Instant now, OpsProperties p) {
        Long oldest = ageSeconds(f.oldestQueuedAt(), now);
        Severity backlog = backlogSeverity(oldest, p.getBacklogWarning(), p.getBacklogCritical());
        ComponentStatus status;
        if (backlog == Severity.CRITICAL) status = ComponentStatus.UNAVAILABLE;
        else if (backlog == Severity.WARNING || f.failedBurst() >= p.getFailureBurst() || f.leaseExpired() > 0) status = ComponentStatus.DEGRADED;
        else status = ComponentStatus.HEALTHY;
        return new JobsSection(status, f.queued(), f.assigned(), f.running(), f.leaseExpired(), f.failedRecent(), f.succeededRecent(), oldest,
                backlog, p.getBacklogWarning().toSeconds(), p.getBacklogCritical().toSeconds(), p.getRecentWindow().toHours());
    }

    // ------------------------------------------------------------------ schedulers

    public static Duration staleAfter(Duration cadence, OpsProperties p) {
        if (cadence.isZero()) return Duration.ZERO;
        Duration scaled = cadence.multipliedBy(p.getStaleCadenceMultiplier());
        return scaled.compareTo(p.getStaleFloor()) < 0 ? p.getStaleFloor() : scaled;
    }

    /** One logical row per scheduler, aggregated over every replica's report; replicas never multiply rows. */
    public static SchedulerRow schedulerRow(SchedulerOperationTracker.Snapshot inventory, List<SchedulerInstance> reports, Duration cadence,
            boolean enabled, OpsProperties p, Instant now) {
        List<SchedulerInstance> mine = reports.stream().filter(r -> r.scheduler().equals(inventory.name())).toList();
        Duration staleAfter = staleAfter(cadence, p);
        Instant started = max(mine.stream().map(SchedulerInstance::lastStartedAt));
        Instant completed = max(mine.stream().map(SchedulerInstance::lastCompletedAt));
        Instant succeeded = max(mine.stream().map(SchedulerInstance::lastSucceededAt));
        Instant failed = max(mine.stream().map(SchedulerInstance::lastFailedAt));
        SchedulerInstance latest = mine.stream().filter(r -> r.lastCompletedAt() != null)
                .max(Comparator.comparing(SchedulerInstance::lastCompletedAt)).orElse(null);
        boolean failedNewest = failed != null && (succeeded == null || failed.isAfter(succeeded));
        String failureCode = failedNewest ? mine.stream().filter(r -> failed.equals(r.lastFailedAt())).map(SchedulerInstance::lastFailureCode)
                .filter(Objects::nonNull).findFirst().orElse(null) : null;
        boolean running = mine.stream().anyMatch(r -> "RUNNING".equals(r.state()) && r.lastStartedAt() != null
                && Duration.between(r.lastStartedAt(), now).compareTo(staleAfter.isZero() ? Duration.ofMinutes(10) : staleAfter) <= 0
                && (r.lastCompletedAt() == null || r.lastStartedAt().isAfter(r.lastCompletedAt())));
        int reporting = (int) mine.stream().filter(r -> r.updatedAt() != null
                && Duration.between(r.updatedAt(), now).compareTo(staleAfter.isZero() ? Duration.ofDays(1) : staleAfter) <= 0).count();

        boolean onDemand = cadence.isZero();
        boolean stale = false;
        SchedulerState state;
        if (!enabled) {
            state = SchedulerState.IDLE;
        } else if (onDemand || mine.isEmpty()) {
            state = SchedulerState.UNKNOWN;
        } else {
            Instant reference = succeeded != null ? succeeded : max(java.util.stream.Stream.of(completed, started));
            stale = reference == null || Duration.between(reference, now).compareTo(staleAfter) > 0;
            if (failedNewest && (succeeded == null || stale)) state = SchedulerState.FAILED;
            else if (failedNewest || stale) state = SchedulerState.DEGRADED;
            else if (running) state = SchedulerState.RUNNING;
            else state = SchedulerState.IDLE;
        }
        return new SchedulerRow(inventory.name(), enabled, state, stale, cadence.toSeconds(), staleAfter.toSeconds(), started, completed,
                succeeded, failed, latest == null ? null : latest.lastDurationMs(), latest == null ? 0 : latest.processedCount(),
                latest == null ? 0 : latest.resultCount(), failureCode, reporting, inventory.batchBound(), inventory.classification());
    }

    public static List<SchedulerRow> schedulerRows(List<SchedulerOperationTracker.Snapshot> inventory, List<SchedulerInstance> reports,
            java.util.function.Function<String, Duration> cadence, java.util.function.Predicate<String> enabled, OpsProperties p, Instant now) {
        return inventory.stream().map(i -> schedulerRow(i, reports, cadence.apply(i.name()), enabled.test(i.name()), p, now))
                .sorted(Comparator.comparing(SchedulerRow::name)).toList();
    }

    public static SchedulersSection schedulersSection(List<SchedulerRow> rows) {
        int idle = count(rows, SchedulerState.IDLE), running = count(rows, SchedulerState.RUNNING), degraded = count(rows, SchedulerState.DEGRADED),
                failed = count(rows, SchedulerState.FAILED), unknown = count(rows, SchedulerState.UNKNOWN);
        ComponentStatus status = failed > 0 ? ComponentStatus.UNAVAILABLE : degraded > 0 ? ComponentStatus.DEGRADED
                : unknown == rows.size() ? ComponentStatus.UNKNOWN : ComponentStatus.HEALTHY;
        return new SchedulersSection(status, rows.size(), idle, running, degraded, failed, unknown);
    }

    private static int count(List<SchedulerRow> rows, SchedulerState state) {
        return (int) rows.stream().filter(r -> r.state() == state).count();
    }

    // ------------------------------------------------------------------ publishing & automation

    public static PublishingSection publishingSection(PublishingFacts f, Instant now, OpsProperties p) {
        Long oldest = ageSeconds(f.oldestDueAt(), now);
        Severity overdue = backlogSeverity(oldest, p.getOverdueWarning(), p.getOverdueCritical());
        ComponentStatus status;
        if (overdue == Severity.CRITICAL) status = ComponentStatus.UNAVAILABLE;
        else if (overdue == Severity.WARNING || f.outcomeUnknown() > 0 || f.failedBurst() >= p.getFailureBurst()) status = ComponentStatus.DEGRADED;
        else status = ComponentStatus.HEALTHY;
        return new PublishingSection(status, f.scheduledDue(), f.scheduledOverdue(), f.publishing(), f.publishedRecent(), f.failedRecent(),
                f.outcomeUnknown(), oldest, p.getOverdueWarning().toSeconds(), p.getOverdueCritical().toSeconds(), p.getRecentWindow().toHours());
    }

    public static List<ProviderRow> providerRows(List<ProviderFact> facts, OpsProperties p) {
        return facts.stream().map(f -> new ProviderRow(f.provider(),
                f.failuresSinceLastSuccess() >= p.getFailureBurst() ? ComponentStatus.DEGRADED
                        : f.lastSuccessAt() == null && f.lastFailureAt() == null ? ComponentStatus.UNKNOWN : ComponentStatus.HEALTHY,
                f.lastSuccessAt(), f.lastFailureAt(), f.failuresSinceLastSuccess())).sorted(Comparator.comparing(ProviderRow::provider)).toList();
    }

    public static AutomationSection automationSection(AutomationFacts f) {
        return new AutomationSection(f.openRollbackRecommendations() > 0 ? ComponentStatus.DEGRADED : ComponentStatus.HEALTHY,
                f.autoProposeRobots(), f.pendingProposals(), f.activeAuthorizations(), f.guardrailBlocked(), f.activeSafetyObservations(),
                f.openRollbackRecommendations(), f.memorySuppressedTransitions());
    }

    // ------------------------------------------------------------------ incidents

    /** Everything the incident rules look at, already reduced to sections. */
    public record Evidence(List<Dependency> dependencies, WorkersSection workers, JobsSection jobs, List<SchedulerRow> schedulers,
            PublishingSection publishing, List<ProviderRow> providers, List<RollbackFact> openRollbacks, long jobFailuresInBurstWindow,
            long publishingFailuresInBurstWindow, int failureBurst) {}

    public static List<DerivedIncident> deriveIncidents(Evidence e) {
        List<DerivedIncident> out = new ArrayList<>();
        for (Dependency d : e.dependencies()) {
            if (!d.configured() || d.status() != ComponentStatus.UNAVAILABLE) continue;
            out.add(dependencyIncident(d));
        }
        WorkersSection w = e.workers();
        boolean queued = e.jobs().queued() > 0;
        if (w.total() == 0) {
            out.add(new DerivedIncident("WORKERS:NONE_REGISTERED", queued ? Severity.WARNING : Severity.INFO, CATEGORY_WORKERS,
                    "No Worker is registered", "NONE_REGISTERED", "registered=0 queuedJobs=" + e.jobs().queued(),
                    "Register a Worker agent if you need media processing; queued Jobs are not processed without one.", "WORKERS", null));
        } else if (w.online() + w.stale() == 0) {
            out.add(new DerivedIncident("WORKERS:NONE_ONLINE", queued ? Severity.CRITICAL : Severity.WARNING, CATEGORY_WORKERS,
                    "No Worker is online", "NONE_ONLINE", "registered=" + w.total() + " online=0 queuedJobs=" + e.jobs().queued(),
                    "Start or restart a Worker agent for this workspace; queued Jobs are not processed without one.", "WORKERS", null));
        } else if (w.stale() > 0 || w.recentlySeenOffline() > 0) {
            out.add(new DerivedIncident("WORKERS:PARTIAL", Severity.INFO, CATEGORY_WORKERS, "Some Workers are not online", "PARTIAL",
                    "online=" + w.online() + " stale=" + w.stale() + " recentlyOffline=" + w.recentlySeenOffline(),
                    "Check the offline Workers listed on the Workers panel; capacity is reduced while they are away.", "WORKERS", null));
        }
        JobsSection j = e.jobs();
        if (j.backlogSeverity() != null) {
            out.add(new DerivedIncident("JOBS:BACKLOG", j.backlogSeverity(), CATEGORY_JOBS, "Job queue is backing up", "QUEUE_BACKLOG",
                    "oldestQueuedAgeSeconds=" + j.oldestQueuedAgeSeconds() + " queued=" + j.queued(),
                    "Confirm Workers are online and support the queued Job types, then inspect the oldest queued Job.", "JOBS", null));
        }
        if (j.leaseExpired() > 0) {
            out.add(new DerivedIncident("JOBS:LEASE_EXPIRED", Severity.WARNING, CATEGORY_JOBS, "Jobs hold an expired lease", "LEASE_EXPIRED",
                    "expiredLeases=" + j.leaseExpired(),
                    "Expired leases are recovered when a Worker next claims work; confirm a Worker is online.", "JOBS", null));
        }
        if (e.jobFailuresInBurstWindow() >= e.failureBurst()) {
            out.add(new DerivedIncident("JOBS:FAILURE_BURST", Severity.WARNING, CATEGORY_JOBS, "Jobs are failing repeatedly", "FAILURE_BURST",
                    "failedInBurstWindow=" + e.jobFailuresInBurstWindow(),
                    "Inspect the failed Jobs for a shared failure category before re-importing or retrying.", "JOBS", null));
        }
        for (SchedulerRow s : e.schedulers()) {
            if (!s.enabled()) continue;
            if (s.stale() && s.cadenceSeconds() > 0) {
                out.add(new DerivedIncident("SCHEDULER:" + s.name() + ":STALE",
                        CRITICAL_PATH_SCHEDULERS.contains(s.name()) ? Severity.CRITICAL : Severity.WARNING, CATEGORY_SCHEDULER,
                        "Scheduler has not completed recently", "STALE", "scheduler=" + s.name() + " staleAfterSeconds=" + s.staleAfterSeconds(),
                        "Confirm at least one API replica is running and check its logs for the scheduler.", "SCHEDULER", null));
            }
            if (s.state() == SchedulerState.FAILED) {
                out.add(new DerivedIncident("SCHEDULER:" + s.name() + ":FAILED", Severity.WARNING, CATEGORY_SCHEDULER, "Scheduler is failing",
                        "FAILED", "scheduler=" + s.name() + " failureCode=" + safeCode(s.lastFailureCode()),
                        "Check the failure code on the Schedulers panel and the API logs; the scheduler retries on its next tick.", "SCHEDULER", null));
            }
        }
        PublishingSection pub = e.publishing();
        Severity overdue = backlogSeverity(pub.oldestOverdueAgeSeconds(), Duration.ofSeconds(pub.overdueWarningSeconds()),
                Duration.ofSeconds(pub.overdueCriticalSeconds()));
        if (overdue != null) {
            out.add(new DerivedIncident("PUBLISHING:OVERDUE", overdue, CATEGORY_PUBLISHING, "Scheduled publications are overdue", "OVERDUE",
                    "oldestDueAgeSeconds=" + pub.oldestOverdueAgeSeconds() + " due=" + pub.scheduledDue(),
                    "Check the publish scheduler and a Worker that supports PUBLISH_MEDIA; due schedules are not being dispatched.", "PUBLISHING", null));
        }
        if (pub.outcomeUnknown() > 0) {
            out.add(new DerivedIncident("PUBLISHING:OUTCOME_UNKNOWN", Severity.CRITICAL, CATEGORY_PUBLISHING,
                    "Publications have an unknown outcome", "OUTCOME_UNKNOWN", "ambiguous=" + pub.outcomeUnknown(),
                    "Verify the post on the provider before any retry; the platform never retries an ambiguous publication automatically.",
                    "PUBLISHING", null));
        }
        if (e.publishingFailuresInBurstWindow() >= e.failureBurst()) {
            out.add(new DerivedIncident("PUBLISHING:FAILURE_BURST", Severity.WARNING, CATEGORY_PUBLISHING, "Publications are failing repeatedly",
                    "FAILURE_BURST", "failedInBurstWindow=" + e.publishingFailuresInBurstWindow(),
                    "Inspect the recent failed publications for a shared failure category and verify provider credentials.", "PUBLISHING", null));
        }
        for (ProviderRow r : e.providers()) {
            if (r.status() == ComponentStatus.DEGRADED) {
                String provider = r.provider().toUpperCase(Locale.ROOT);
                out.add(new DerivedIncident("PUBLISHING:PROVIDER:" + provider + ":FAILING", Severity.WARNING, CATEGORY_PUBLISHING,
                        "Provider publications keep failing", "PROVIDER_FAILING",
                        "provider=" + provider + " failuresSinceLastSuccess=" + r.failuresSinceLastSuccess(),
                        "Reconnect or verify the " + provider + " account; there has been no success since the failures began.", "PROVIDER", null));
            }
        }
        for (RollbackFact r : e.openRollbacks()) {
            out.add(new DerivedIncident("AUTOMATION:ROLLBACK:" + r.id(), Severity.WARNING, CATEGORY_AUTOMATION,
                    "A rollback recommendation is open", "ROLLBACK_RECOMMENDATION_OPEN", "status=" + r.status(),
                    "Open the Robot's adaptive lifecycle and acknowledge, dismiss or roll back the recommendation.",
                    "ROLLBACK_RECOMMENDATION", r.id()));
        }
        return out.stream().sorted(Comparator.comparing((DerivedIncident i) -> -i.severity().ordinal()).thenComparing(DerivedIncident::key)).toList();
    }

    private static DerivedIncident dependencyIncident(Dependency d) {
        String name = d.name().toUpperCase(Locale.ROOT);
        Severity severity;
        String title;
        String action;
        switch (name) {
            case "POSTGRES" -> {
                severity = Severity.CRITICAL;
                title = "PostgreSQL is unavailable";
                action = "Check the PostgreSQL container health and connectivity; the API cannot persist state until it recovers.";
            }
            case "MINIO" -> {
                severity = Severity.CRITICAL;
                title = "Object storage (MinIO) is unavailable";
                action = "Check MinIO health and credentials; media import, clips and publishing are blocked until it recovers.";
            }
            case "RABBITMQ" -> {
                severity = Severity.INFO;
                title = "RabbitMQ is unavailable";
                action = "RabbitMQ is optional for the current feature set; check the broker only if you rely on it.";
            }
            default -> {
                severity = Severity.WARNING;
                title = d.name() + " is unavailable";
                action = "Check the health of " + d.name() + " and the API logs.";
            }
        }
        return new DerivedIncident("DEPENDENCY:" + name + ":UNAVAILABLE", severity, CATEGORY_DEPENDENCY, title, "UNAVAILABLE",
                "reason=" + safeCode(d.detail()), action, "DEPENDENCY", null);
    }

    // ------------------------------------------------------------------ overall

    /** ACTION_REQUIRED if any CRITICAL incident is active, DEGRADED if any WARNING is active, otherwise HEALTHY. INFO never degrades. */
    public static OverallStatus overall(List<Incident> active) {
        if (active.stream().anyMatch(i -> i.severity() == Severity.CRITICAL)) return OverallStatus.ACTION_REQUIRED;
        if (active.stream().anyMatch(i -> i.severity() == Severity.WARNING)) return OverallStatus.DEGRADED;
        return OverallStatus.HEALTHY;
    }

    public static ComponentStatus incidentsStatus(IncidentsSection s) {
        return s.critical() + s.warning() > 0 ? ComponentStatus.DEGRADED : ComponentStatus.HEALTHY;
    }

    public static IncidentsSection incidentsSection(List<Incident> active) {
        long critical = active.stream().filter(i -> i.severity() == Severity.CRITICAL).count();
        long warning = active.stream().filter(i -> i.severity() == Severity.WARNING).count();
        long info = active.stream().filter(i -> i.severity() == Severity.INFO).count();
        long unacknowledged = active.stream().filter(i -> !i.acknowledged()).count();
        IncidentsSection draft = new IncidentsSection(ComponentStatus.HEALTHY, active.size(), critical, warning, info, unacknowledged);
        return new IncidentsSection(incidentsStatus(draft), active.size(), critical, warning, info, unacknowledged);
    }

    private static String safeCode(String raw) {
        String category = OpsSanitizer.category(raw);
        return category == null ? "NONE" : category;
    }

    private static Instant max(java.util.stream.Stream<Instant> values) {
        return values.filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    /** Stable display order: CRITICAL first, then WARNING, then INFO; oldest first inside a severity; key as the final tiebreaker. */
    public static final Comparator<Incident> INCIDENT_ORDER = Comparator.comparing((Incident i) -> -i.severity().ordinal())
            .thenComparing(Incident::firstObservedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Incident::key);
}
