package com.fdmultimedia.api.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fdmultimedia.api.opscontrol.OpsFacts.*;
import com.fdmultimedia.api.opscontrol.OpsModels.*;
import com.fdmultimedia.api.shared.operations.ApiInstanceIdentity;
import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import com.fdmultimedia.api.workers.WorkerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** Whole read model over the fake store: the same code path the controller uses, without a database. */
class OperationsControlServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final UUID WS = UUID.randomUUID();

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FakeOpsStore store = new FakeOpsStore();
    private final OpsProperties properties = new OpsProperties();
    private final SchedulerOperationTracker tracker = new SchedulerOperationTracker(clock);
    private DependencyHealthService dependencies;
    private OperationsControlService service;
    private DependencyHealthService.ProbeResult minio = new DependencyHealthService.ProbeResult(ComponentStatus.HEALTHY, "UP");

    private static com.fdmultimedia.api.shared.operations.ReleaseInfo release() {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.BuildProperties> none = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        return new com.fdmultimedia.api.shared.operations.ReleaseInfo(none, "0123456789abcdef");
    }

    private OperationsControlService build() {
        DependencyHealthService.Probe postgres = probe("POSTGRES", () -> new DependencyHealthService.ProbeResult(ComponentStatus.HEALTHY, "UP"));
        DependencyHealthService.Probe storage = probe("MINIO", () -> minio);
        dependencies = new DependencyHealthService(List.of(postgres, storage), properties, Clock.systemUTC());
        var snapshots = new OpsSnapshotService(store, dependencies, properties, new WorkerProperties(), tracker,
                new SchedulerCadencePolicy(new MockEnvironment()), clock);
        service = new OperationsControlService(snapshots, new OpsIncidentService(store, clock), store, properties, tracker, new ApiInstanceIdentity(), release(), clock);
        return service;
    }

    @AfterEach
    void tearDown() { if (dependencies != null) dependencies.shutdown(); }

    private static DependencyHealthService.Probe probe(String name, java.util.function.Supplier<DependencyHealthService.ProbeResult> result) {
        return new DependencyHealthService.Probe() {
            public String name() { return name; }
            public boolean configured() { return true; }
            public DependencyHealthService.ProbeResult probe() { return result.get(); }
        };
    }

    private void healthyFleet() {
        store.workers = List.of(new WorkerFact(UUID.randomUUID(), "w1", NOW.minusSeconds(3), 0, 2, List.of("SYSTEM_TEST"), "1", null, null, null));
        for (String name : List.of("publish-schedule-dispatch", "publication-analytics", "robot-automation", "autonomous-proposals",
                "adaptive-execution", "post-change-safety", "adaptive-memory", "scheduling-decision-retention", "operations-incidents")) {
            store.schedulerInstances.add(new SchedulerInstance(name, "api-1", "SUCCEEDED", NOW.minusSeconds(2), NOW.minusSeconds(1), NOW.minusSeconds(1),
                    null, 5L, 1, 1, null, NOW.minusSeconds(1)));
        }
    }

    @Test
    void healthyPlatformReportsHealthyOverviewWithoutIncidents() {
        healthyFleet();
        Overview o = build().overview(WS);
        assertThat(o.engineVersion()).isEqualTo("OPERATIONS_OVERVIEW_V1");
        assertThat(o.api().commit()).isEqualTo("0123456789ab");
        assertThat(o.overallStatus()).isEqualTo(OverallStatus.HEALTHY);
        assertThat(o.topIncidents()).isEmpty();
        assertThat(o.workers().online()).isEqualTo(1);
        assertThat(o.jobs().oldestQueuedAgeSeconds()).isNull();
        assertThat(o.schedulers().total()).isEqualTo(11);
        assertThat(o.schedulers().failed()).isZero();
        assertThat(o.dependencies()).extracting(Dependency::name).contains("POSTGRES", "MINIO");
        assertThat(o.refreshHintSeconds()).isBetween(15L, 30L);
    }

    @Test
    void minioOutageSurfacesAsCriticalIncidentThenResolvesOnRecovery() throws Exception {
        healthyFleet();
        properties.setProbeCacheTtl(Duration.ofSeconds(1));
        minio = new DependencyHealthService.ProbeResult(ComponentStatus.UNAVAILABLE, "UNREACHABLE");
        build();
        Overview down = service.overview(WS);
        assertThat(down.overallStatus()).isEqualTo(OverallStatus.ACTION_REQUIRED);
        assertThat(down.topIncidents()).extracting(Incident::key).containsExactly("DEPENDENCY:MINIO:UNAVAILABLE");
        assertThat(down.topIncidents().get(0).persisted()).isTrue();
        minio = new DependencyHealthService.ProbeResult(ComponentStatus.HEALTHY, "UP");
        Thread.sleep(1_100);
        Overview up = service.overview(WS);
        assertThat(up.overallStatus()).isEqualTo(OverallStatus.HEALTHY);
        assertThat(up.topIncidents()).isEmpty();
        assertThat(service.incidents(WS, "RESOLVED", null, 0, 25).incidents().items()).extracting(Incident::key)
                .containsExactly("DEPENDENCY:MINIO:UNAVAILABLE");
    }

    @Test
    void overviewIsReadOnlyAgainstBusinessState() {
        healthyFleet();
        build().overview(WS);
        // the only store writes allowed are incident bookkeeping
        assertThat(store.inserts + store.updates + store.resolves).isZero();
    }

    @Test
    void topIncidentsAreBoundedAndOrderedBySeverity() {
        healthyFleet();
        store.rollbacks = java.util.stream.IntStream.range(0, 8).mapToObj(i -> new RollbackFact(UUID.randomUUID(), NOW.minusSeconds(i), "OPEN")).toList();
        store.publishingFacts = new PublishingFacts(0, 0, 0, 0, 0, 0, 1, null);
        Overview o = build().overview(WS);
        assertThat(o.topIncidents()).hasSize(5);
        assertThat(o.topIncidents().get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(o.incidents().active()).isEqualTo(9);
    }

    @Test
    void workersPageFiltersByStateAndIsPaginated() {
        store.workers = List.of(worker("a", 1), worker("b", 25), worker("c", 400), worker("d", 2));
        build();
        assertThat(service.workers(WS, "ONLINE", 0, 10).workers().items()).extracting(WorkerRow::name).containsExactly("a", "d");
        assertThat(service.workers(WS, "stale", 0, 10).workers().items()).extracting(WorkerRow::name).containsExactly("b");
        var page = service.workers(WS, null, 1, 2).workers();
        assertThat(page.items()).extracting(WorkerRow::name).containsExactly("b", "c");
        assertThat(page.total()).isEqualTo(4);
        assertThat(service.workers(WS, null, 0, 1000).workers().size()).isEqualTo(OpsStore.MAX_PAGE);
        assertThatThrownBy(() -> service.workers(WS, "BROKEN", 0, 10)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void jobFiltersAreValidatedAndDetailIsWorkspaceScoped() {
        UUID id = UUID.randomUUID();
        store.jobRows = List.of(new JobRow(id, "PUBLISH_MEDIA", "FAILED", NOW, NOW, 3, 3, "EXHAUSTED", "PROVIDER_REJECTED", "msg", null));
        build();
        assertThat(service.jobs(WS, "FAILED", "PUBLISH_MEDIA", 0, 25).jobs().items()).hasSize(1);
        assertThat(service.jobs(WS, "SUCCEEDED", null, 0, 25).jobs().items()).isEmpty();
        assertThatThrownBy(() -> service.jobs(WS, "NOPE", null, 0, 25)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.jobs(WS, null, "NOPE", 0, 25)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.job(WS, id).job().retryState()).isEqualTo("EXHAUSTED");
        assertThatThrownBy(() -> service.job(WS, UUID.randomUUID())).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void incidentsListSyncsBeforeReading() {
        healthyFleet();
        store.publishingFacts = new PublishingFacts(0, 0, 0, 0, 0, 0, 2, null);
        build();
        var view = service.incidents(WS, "ACTIVE", null, 0, 25);
        assertThat(view.incidents().items()).extracting(Incident::key).containsExactly("PUBLISHING:OUTCOME_UNKNOWN");
        assertThat(view.summary().critical()).isEqualTo(1);
        assertThat(view.summary().unacknowledged()).isEqualTo(1);
        assertThatThrownBy(() -> service.incidents(WS, "NOPE", null, 0, 25)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.incidents(WS, "ACTIVE", "LOUD", 0, 25)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void publishingViewExposesProviderEvidenceAndAmbiguousPublicationsWithoutRetry() {
        healthyFleet();
        store.providers = List.of(new ProviderFact("TEST", NOW.minusSeconds(30), null, 0));
        store.publications = List.of(new PublicationRow(UUID.randomUUID(), "TIKTOK", "PUBLISHING", NOW, null, 1, null, true, "VERIFY_WITH_PROVIDER_BEFORE_ANY_RETRY"));
        var view = build().publishing(WS, 0, 25);
        assertThat(view.providers()).hasSize(1);
        assertThat(view.attention().items().get(0).retryGuidance()).startsWith("VERIFY");
    }

    @Test
    void schedulerInventoryHasOneLogicalRowPerSchedulerRegardlessOfReplicas() {
        healthyFleet();
        for (int i = 2; i <= 4; i++) {
            store.schedulerInstances.add(new SchedulerInstance("robot-automation", "api-" + i, "SUCCEEDED", NOW.minusSeconds(4), NOW.minusSeconds(3),
                    NOW.minusSeconds(3), null, 5L, 0, 0, null, NOW.minusSeconds(3)));
        }
        var view = build().schedulers(WS);
        assertThat(view.schedulers()).hasSize(11);
        assertThat(view.schedulers().stream().filter(r -> r.name().equals("robot-automation")).findFirst().orElseThrow().reportingInstances()).isEqualTo(4);
    }

    private WorkerFact worker(String name, int secondsAgo) {
        return new WorkerFact(UUID.randomUUID(), name, NOW.minusSeconds(secondsAgo), 0, 2, List.of(), "1", null, null, null);
    }
}
