package com.fdmultimedia.api.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fdmultimedia.api.opscontrol.OpsFacts.*;
import com.fdmultimedia.api.opscontrol.OpsModels.*;
import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpsRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration HEARTBEAT = Duration.ofSeconds(10);
    private static final Duration OFFLINE = Duration.ofSeconds(30);
    private final OpsProperties props = new OpsProperties();

    // ---- workers ----

    @Test
    void workerStatesFollowHeartbeatAndOfflineThresholds() {
        assertThat(OpsRules.workerState(NOW.minusSeconds(5), NOW, HEARTBEAT, OFFLINE)).isEqualTo(WorkerState.ONLINE);
        assertThat(OpsRules.workerState(NOW.minusSeconds(20), NOW, HEARTBEAT, OFFLINE)).isEqualTo(WorkerState.ONLINE);
        assertThat(OpsRules.workerState(NOW.minusSeconds(21), NOW, HEARTBEAT, OFFLINE)).isEqualTo(WorkerState.STALE);
        assertThat(OpsRules.workerState(NOW.minusSeconds(30), NOW, HEARTBEAT, OFFLINE)).isEqualTo(WorkerState.STALE);
        assertThat(OpsRules.workerState(NOW.minusSeconds(31), NOW, HEARTBEAT, OFFLINE)).isEqualTo(WorkerState.OFFLINE);
        assertThat(OpsRules.workerState(null, NOW, HEARTBEAT, OFFLINE)).isEqualTo(WorkerState.OFFLINE);
    }

    @Test
    void workerSectionCountsAndStatus() {
        List<WorkerRow> rows = OpsRules.workerRows(List.of(worker("b", 5), worker("a", 25), worker("c", 3600)), NOW, HEARTBEAT, OFFLINE);
        // ONLINE first, then STALE, then OFFLINE; name only breaks ties inside a state
        assertThat(rows).extracting(WorkerRow::name).containsExactly("b", "a", "c");
        WorkersSection s = OpsRules.workersSection(rows, NOW, Duration.ofHours(24), HEARTBEAT, OFFLINE);
        assertThat(s.online()).isEqualTo(1);
        assertThat(s.stale()).isEqualTo(1);
        assertThat(s.offline()).isEqualTo(1);
        assertThat(s.recentlySeenOffline()).isEqualTo(1);
        assertThat(s.status()).isEqualTo(ComponentStatus.DEGRADED);
        assertThat(OpsRules.workersSection(List.of(), NOW, Duration.ofHours(24), HEARTBEAT, OFFLINE).status()).isEqualTo(ComponentStatus.UNKNOWN);
        List<WorkerRow> offline = OpsRules.workerRows(List.of(worker("x", 600)), NOW, HEARTBEAT, OFFLINE);
        assertThat(OpsRules.workersSection(offline, NOW, Duration.ofHours(24), HEARTBEAT, OFFLINE).status()).isEqualTo(ComponentStatus.UNAVAILABLE);
    }

    @Test
    void anOldOfflineWorkerDoesNotDegradeAnOtherwiseHealthyFleet() {
        List<WorkerRow> rows = OpsRules.workerRows(List.of(worker("live", 2), worker("retired", 3 * 86_400)), NOW, HEARTBEAT, OFFLINE);
        WorkersSection s = OpsRules.workersSection(rows, NOW, Duration.ofHours(24), HEARTBEAT, OFFLINE);
        assertThat(s.offline()).isEqualTo(1);
        assertThat(s.recentlySeenOffline()).isZero();
        assertThat(s.status()).isEqualTo(ComponentStatus.HEALTHY);
    }

    // ---- jobs ----

    @Test
    void backlogThresholdsAreExactAndNullWhenNothingIsQueued() {
        assertThat(OpsRules.backlogSeverity(null, props.getBacklogWarning(), props.getBacklogCritical())).isNull();
        assertThat(OpsRules.backlogSeverity(300L, props.getBacklogWarning(), props.getBacklogCritical())).isNull();
        assertThat(OpsRules.backlogSeverity(301L, props.getBacklogWarning(), props.getBacklogCritical())).isEqualTo(Severity.WARNING);
        assertThat(OpsRules.backlogSeverity(1800L, props.getBacklogWarning(), props.getBacklogCritical())).isEqualTo(Severity.WARNING);
        assertThat(OpsRules.backlogSeverity(1801L, props.getBacklogWarning(), props.getBacklogCritical())).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void emptyQueueHasNullOldestAgeAndIsHealthy() {
        JobsSection s = OpsRules.jobsSection(new JobFacts(0, 0, 0, 0, 0, 7, 0, null), NOW, props);
        assertThat(s.oldestQueuedAgeSeconds()).isNull();
        assertThat(s.backlogSeverity()).isNull();
        assertThat(s.status()).isEqualTo(ComponentStatus.HEALTHY);
    }

    @Test
    void oldQueuedJobDegradesAndCriticalBacklogIsUnavailable() {
        assertThat(OpsRules.jobsSection(jobs(NOW.minusSeconds(600)), NOW, props).status()).isEqualTo(ComponentStatus.DEGRADED);
        JobsSection critical = OpsRules.jobsSection(jobs(NOW.minus(Duration.ofHours(1))), NOW, props);
        assertThat(critical.status()).isEqualTo(ComponentStatus.UNAVAILABLE);
        assertThat(critical.backlogSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(critical.oldestQueuedAgeSeconds()).isEqualTo(3600);
    }

    // ---- schedulers ----

    @Test
    void stalenessIsCadenceAware() {
        var hourly = inventory(SchedulerOperationTracker.ADAPTIVE_MEMORY);
        var fast = inventory(SchedulerOperationTracker.PUBLISH_SCHEDULE);
        List<SchedulerInstance> reports = List.of(report(hourly.name(), "i1", NOW.minus(Duration.ofMinutes(30)), null),
                report(fast.name(), "i1", NOW.minus(Duration.ofMinutes(30)), null));
        SchedulerRow hourlyRow = OpsRules.schedulerRow(hourly, reports, Duration.ofHours(1), true, props, NOW);
        SchedulerRow fastRow = OpsRules.schedulerRow(fast, reports, Duration.ofSeconds(15), true, props, NOW);
        assertThat(hourlyRow.stale()).isFalse();
        assertThat(hourlyRow.state()).isEqualTo(SchedulerState.IDLE);
        assertThat(hourlyRow.staleAfterSeconds()).isEqualTo(3 * 3600);
        assertThat(fastRow.stale()).isTrue();
        assertThat(fastRow.state()).isEqualTo(SchedulerState.DEGRADED);
        assertThat(fastRow.staleAfterSeconds()).isEqualTo(120);
    }

    @Test
    void replicasAreAggregatedIntoOneLogicalRow() {
        var def = inventory(SchedulerOperationTracker.ROBOT_AUTOMATION);
        List<SchedulerInstance> reports = List.of(report(def.name(), "api-1", NOW.minusSeconds(40), null),
                report(def.name(), "api-2", NOW.minusSeconds(5), null), report(def.name(), "api-3", NOW.minusSeconds(20), null),
                report(def.name(), "api-4", NOW.minusSeconds(10), null));
        List<SchedulerRow> rows = OpsRules.schedulerRows(List.of(def), reports, n -> Duration.ofSeconds(15), n -> true, props, NOW);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).reportingInstances()).isEqualTo(4);
        assertThat(rows.get(0).lastSucceededAt()).isEqualTo(NOW.minusSeconds(5));
        assertThat(rows.get(0).state()).isEqualTo(SchedulerState.IDLE);
    }

    @Test
    void failureNewerThanSuccessIsDegradedThenFailedWhenSuccessIsStale() {
        var def = inventory(SchedulerOperationTracker.POST_CHANGE_SAFETY);
        SchedulerInstance recentFailure = new SchedulerInstance(def.name(), "i1", "FAILED", NOW.minusSeconds(70), NOW.minusSeconds(60),
                NOW.minus(Duration.ofMinutes(61)), NOW.minusSeconds(60), 10L, 0, 0, "IllegalStateException", NOW.minusSeconds(60));
        SchedulerRow degraded = OpsRules.schedulerRow(def, List.of(recentFailure), Duration.ofHours(1), true, props, NOW);
        assertThat(degraded.state()).isEqualTo(SchedulerState.DEGRADED);
        assertThat(degraded.lastFailureCode()).isEqualTo("IllegalStateException");
        SchedulerInstance longFailing = new SchedulerInstance(def.name(), "i1", "FAILED", NOW.minusSeconds(70), NOW.minusSeconds(60),
                NOW.minus(Duration.ofHours(5)), NOW.minusSeconds(60), 10L, 0, 0, "IllegalStateException", NOW.minusSeconds(60));
        assertThat(OpsRules.schedulerRow(def, List.of(longFailing), Duration.ofHours(1), true, props, NOW).state()).isEqualTo(SchedulerState.FAILED);
        SchedulerInstance neverSucceeded = new SchedulerInstance(def.name(), "i1", "FAILED", NOW.minusSeconds(70), NOW.minusSeconds(60),
                null, NOW.minusSeconds(60), 10L, 0, 0, "X", NOW.minusSeconds(60));
        assertThat(OpsRules.schedulerRow(def, List.of(neverSucceeded), Duration.ofHours(1), true, props, NOW).state()).isEqualTo(SchedulerState.FAILED);
    }

    @Test
    void unknownDisabledRunningAndOnDemandStates() {
        var def = inventory(SchedulerOperationTracker.AUTONOMOUS_PROPOSALS);
        assertThat(OpsRules.schedulerRow(def, List.of(), Duration.ofHours(1), true, props, NOW).state()).isEqualTo(SchedulerState.UNKNOWN);
        assertThat(OpsRules.schedulerRow(def, List.of(), Duration.ofHours(1), false, props, NOW).state()).isEqualTo(SchedulerState.IDLE);
        assertThat(OpsRules.schedulerRow(def, List.of(report(def.name(), "i", NOW.minusSeconds(5), null)), Duration.ZERO, true, props, NOW).state())
                .isEqualTo(SchedulerState.UNKNOWN);
        SchedulerInstance running = new SchedulerInstance(def.name(), "i1", "RUNNING", NOW.minusSeconds(3), NOW.minusSeconds(60),
                NOW.minusSeconds(60), null, 10L, 1, 1, null, NOW.minusSeconds(3));
        assertThat(OpsRules.schedulerRow(def, List.of(running), Duration.ofHours(1), true, props, NOW).state()).isEqualTo(SchedulerState.RUNNING);
    }

    @Test
    void disabledSchedulerNeverRaisesAnIncident() {
        var def = inventory(SchedulerOperationTracker.ROBOT_AUTOMATION);
        SchedulerRow disabled = OpsRules.schedulerRow(def, List.of(), Duration.ofSeconds(15), false, props, NOW);
        assertThat(derive(evidence(List.of(disabled)))).extracting(DerivedIncident::key).noneMatch(k -> k.startsWith("SCHEDULER:"));
    }

    // ---- incidents ----

    @Test
    void healthyPlatformDerivesNoIncidentsAndIsHealthy() {
        List<DerivedIncident> incidents = derive(evidence(List.of()));
        assertThat(incidents).isEmpty();
        assertThat(OpsRules.overall(List.of())).isEqualTo(OverallStatus.HEALTHY);
    }

    @Test
    void minioOutageProducesStableCriticalKeyAndActionRequired() {
        Dependency minio = new Dependency("MINIO", ComponentStatus.UNAVAILABLE, "TIMEOUT", true, NOW, 0, false, 750L);
        OpsRules.Evidence e = new OpsRules.Evidence(List.of(minio), healthyWorkers(), healthyJobs(), List.of(), healthyPublishing(), List.of(),
                List.of(), 0, 0, 3);
        List<DerivedIncident> incidents = OpsRules.deriveIncidents(e);
        assertThat(incidents).singleElement().satisfies(i -> {
            assertThat(i.key()).isEqualTo("DEPENDENCY:MINIO:UNAVAILABLE");
            assertThat(i.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(i.suggestedAction()).contains("MinIO");
        });
        assertThat(OpsRules.deriveIncidents(e)).isEqualTo(incidents);
    }

    @Test
    void unconfiguredOrOptionalDependenciesNeverDegradeOverall() {
        Dependency rabbit = new Dependency("RABBITMQ", ComponentStatus.UNAVAILABLE, "UNREACHABLE", true, NOW, 0, false, 5L);
        Dependency notConfigured = new Dependency("OTHER", ComponentStatus.UNAVAILABLE, "NOT_CONFIGURED", false, null, 0, false, null);
        List<DerivedIncident> incidents = OpsRules.deriveIncidents(new OpsRules.Evidence(List.of(rabbit, notConfigured), healthyWorkers(),
                healthyJobs(), List.of(), healthyPublishing(), List.of(), List.of(), 0, 0, 3));
        assertThat(incidents).singleElement().satisfies(i -> assertThat(i.severity()).isEqualTo(Severity.INFO));
    }

    @Test
    void noOnlineWorkerIsCriticalOnlyWhenWorkIsWaiting() {
        WorkersSection none = new WorkersSection(ComponentStatus.UNAVAILABLE, 2, 0, 0, 2, 2, 20, 30);
        JobsSection idle = OpsRules.jobsSection(new JobFacts(0, 0, 0, 0, 0, 0, 0, null), NOW, props);
        JobsSection waiting = OpsRules.jobsSection(new JobFacts(4, 0, 0, 0, 0, 0, 0, NOW.minusSeconds(30)), NOW, props);
        assertThat(severityOf("WORKERS:NONE_ONLINE", none, idle)).isEqualTo(Severity.WARNING);
        assertThat(severityOf("WORKERS:NONE_ONLINE", none, waiting)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void neverRegisteredWorkerIsInformationalWithoutQueuedWork() {
        WorkersSection none = new WorkersSection(ComponentStatus.UNKNOWN, 0, 0, 0, 0, 0, 20, 30);
        assertThat(severityOf("WORKERS:NONE_REGISTERED", none, healthyJobs())).isEqualTo(Severity.INFO);
    }

    @Test
    void jobBacklogIncidentCarriesSeverityAndFailureBurstUsesItsOwnWindow() {
        JobsSection warn = OpsRules.jobsSection(jobs(NOW.minusSeconds(900)), NOW, props);
        List<DerivedIncident> incidents = OpsRules.deriveIncidents(new OpsRules.Evidence(List.of(), healthyWorkers(), warn, List.of(),
                healthyPublishing(), List.of(), List.of(), 3, 0, 3));
        assertThat(incidents).extracting(DerivedIncident::key).containsExactlyInAnyOrder("JOBS:BACKLOG", "JOBS:FAILURE_BURST");
        assertThat(incidents.stream().filter(i -> i.key().equals("JOBS:BACKLOG")).findFirst().orElseThrow().severity()).isEqualTo(Severity.WARNING);
        List<DerivedIncident> below = OpsRules.deriveIncidents(new OpsRules.Evidence(List.of(), healthyWorkers(), healthyJobs(), List.of(),
                healthyPublishing(), List.of(), List.of(), 2, 2, 3));
        assertThat(below).isEmpty();
    }

    @Test
    void staleSchedulersRaiseKeyedIncidentsAndPublishingIsCritical() {
        var publish = inventory(SchedulerOperationTracker.PUBLISH_SCHEDULE);
        var memory = inventory(SchedulerOperationTracker.ADAPTIVE_MEMORY);
        List<SchedulerRow> rows = OpsRules.schedulerRows(List.of(publish, memory),
                List.of(report(publish.name(), "i", NOW.minus(Duration.ofHours(1)), null), report(memory.name(), "i", NOW.minus(Duration.ofHours(10)), null)),
                n -> n.equals(publish.name()) ? Duration.ofSeconds(15) : Duration.ofHours(1), n -> true, props, NOW);
        List<DerivedIncident> incidents = derive(evidence(rows));
        assertThat(incidents).extracting(DerivedIncident::key)
                .containsExactlyInAnyOrder("SCHEDULER:publish-schedule-dispatch:STALE", "SCHEDULER:adaptive-memory:STALE");
        assertThat(incidents.stream().filter(i -> i.key().contains("publish")).findFirst().orElseThrow().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(incidents.stream().filter(i -> i.key().contains("memory")).findFirst().orElseThrow().severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void publishingConditions() {
        PublishingSection overdue = OpsRules.publishingSection(new PublishingFacts(2, 2, 0, 0, 0, 0, 0, NOW.minus(Duration.ofHours(1))), NOW, props);
        PublishingSection unknown = OpsRules.publishingSection(new PublishingFacts(0, 0, 1, 0, 0, 0, 2, null), NOW, props);
        assertThat(overdue.status()).isEqualTo(ComponentStatus.UNAVAILABLE);
        assertThat(unknown.status()).isEqualTo(ComponentStatus.DEGRADED);
        List<DerivedIncident> incidents = OpsRules.deriveIncidents(new OpsRules.Evidence(List.of(), healthyWorkers(), healthyJobs(), List.of(),
                unknown, List.of(), List.of(), 0, 0, 3));
        assertThat(incidents).singleElement().satisfies(i -> {
            assertThat(i.key()).isEqualTo("PUBLISHING:OUTCOME_UNKNOWN");
            assertThat(i.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(i.suggestedAction()).contains("Verify");
        });
        assertThat(OpsRules.deriveIncidents(new OpsRules.Evidence(List.of(), healthyWorkers(), healthyJobs(), List.of(), overdue, List.of(),
                List.of(), 0, 0, 3))).singleElement().satisfies(i -> assertThat(i.key()).isEqualTo("PUBLISHING:OVERDUE"));
    }

    @Test
    void providerFailuresUseOperationalEvidence() {
        List<ProviderRow> providers = OpsRules.providerRows(List.of(new ProviderFact("TIKTOK", NOW.minus(Duration.ofHours(5)), NOW.minusSeconds(60), 4),
                new ProviderFact("INSTAGRAM", NOW.minusSeconds(60), null, 0)), props);
        assertThat(providers).extracting(ProviderRow::status).containsExactly(ComponentStatus.HEALTHY, ComponentStatus.DEGRADED);
        List<DerivedIncident> incidents = OpsRules.deriveIncidents(new OpsRules.Evidence(List.of(), healthyWorkers(), healthyJobs(), List.of(),
                healthyPublishing(), providers, List.of(), 0, 0, 3));
        assertThat(incidents).singleElement().satisfies(i -> assertThat(i.key()).isEqualTo("PUBLISHING:PROVIDER:TIKTOK:FAILING"));
    }

    @Test
    void openRollbackRecommendationBecomesASubjectIncident() {
        UUID id = UUID.randomUUID();
        List<DerivedIncident> incidents = OpsRules.deriveIncidents(new OpsRules.Evidence(List.of(), healthyWorkers(), healthyJobs(), List.of(),
                healthyPublishing(), List.of(), List.of(new RollbackFact(id, NOW, "OPEN")), 0, 0, 3));
        assertThat(incidents).singleElement().satisfies(i -> {
            assertThat(i.key()).isEqualTo("AUTOMATION:ROLLBACK:" + id);
            assertThat(i.subjectType()).isEqualTo("ROLLBACK_RECOMMENDATION");
            assertThat(i.subjectId()).isEqualTo(id);
            assertThat(i.severity()).isEqualTo(Severity.WARNING);
        });
    }

    @Test
    void overallStatusIsTheWorstActiveSeverityAndInfoNeverDegrades() {
        assertThat(OpsRules.overall(List.of(incident(Severity.INFO)))).isEqualTo(OverallStatus.HEALTHY);
        assertThat(OpsRules.overall(List.of(incident(Severity.INFO), incident(Severity.WARNING)))).isEqualTo(OverallStatus.DEGRADED);
        assertThat(OpsRules.overall(List.of(incident(Severity.WARNING), incident(Severity.CRITICAL)))).isEqualTo(OverallStatus.ACTION_REQUIRED);
    }

    @Test
    void incidentOrderIsStable() {
        Incident a = incident(Severity.WARNING), b = incident(Severity.CRITICAL), c = incident(Severity.INFO);
        assertThat(List.of(a, c, b).stream().sorted(OpsRules.INCIDENT_ORDER).map(Incident::severity).toList())
                .containsExactly(Severity.CRITICAL, Severity.WARNING, Severity.INFO);
    }

    @Test
    void incidentTextNeverContainsSecretsOrUrls() {
        Dependency minio = new Dependency("MINIO", ComponentStatus.UNAVAILABLE, "https://minio:9000/bucket?X-Amz-Signature=abc", true, NOW, 0, false, 1L);
        DerivedIncident d = OpsRules.deriveIncidents(new OpsRules.Evidence(List.of(minio), healthyWorkers(), healthyJobs(), List.of(),
                healthyPublishing(), List.of(), List.of(), 0, 0, 3)).get(0);
        assertThat(d.detail()).isEqualTo("reason=UNCLASSIFIED");
        assertThat(d.detail()).doesNotContain("http").doesNotContain("Signature");
    }

    // ---- helpers ----

    private Severity severityOf(String key, WorkersSection workers, JobsSection jobs) {
        return OpsRules.deriveIncidents(new OpsRules.Evidence(List.of(), workers, jobs, List.of(), healthyPublishing(), List.of(), List.of(), 0, 0, 3))
                .stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow().severity();
    }

    private List<DerivedIncident> derive(OpsRules.Evidence e) { return OpsRules.deriveIncidents(e); }

    private OpsRules.Evidence evidence(List<SchedulerRow> schedulers) {
        return new OpsRules.Evidence(List.of(), healthyWorkers(), healthyJobs(), schedulers, healthyPublishing(), List.of(), List.of(), 0, 0, 3);
    }

    private WorkersSection healthyWorkers() { return new WorkersSection(ComponentStatus.HEALTHY, 1, 1, 0, 0, 0, 20, 30); }
    private JobsSection healthyJobs() { return OpsRules.jobsSection(new JobFacts(0, 0, 0, 0, 0, 0, 0, null), NOW, props); }
    private PublishingSection healthyPublishing() { return OpsRules.publishingSection(new PublishingFacts(0, 0, 0, 0, 0, 0, 0, null), NOW, props); }
    private JobFacts jobs(Instant oldest) { return new JobFacts(3, 0, 0, 0, 0, 0, 0, oldest); }

    private WorkerFact worker(String name, int secondsAgo) {
        return new WorkerFact(UUID.randomUUID(), name, NOW.minusSeconds(secondsAgo), 0, 2, List.of("SYSTEM_TEST"), "1", null, null, null);
    }

    private SchedulerOperationTracker.Snapshot inventory(String name) {
        return new SchedulerOperationTracker.Snapshot(name, "A", 50, "x", null, null, null, null, null, 0, 0, null, "NEVER_RUN");
    }

    private SchedulerInstance report(String scheduler, String instance, Instant succeededAt, Instant failedAt) {
        return new SchedulerInstance(scheduler, instance, "SUCCEEDED", succeededAt.minusSeconds(1), succeededAt, succeededAt, failedAt, 5L, 2, 1, null, succeededAt);
    }

    private Incident incident(Severity severity) {
        return new Incident(UUID.randomUUID(), "K:" + severity, severity, IncidentStatus.ACTIVE, "X", "t", "c", "d", "a", null, null, NOW, NOW, null, null, false, true);
    }
}
