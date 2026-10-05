package com.fdmultimedia.api.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fdmultimedia.api.opscontrol.OpsModels.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OpsIncidentServiceTest {
    private static final UUID WS = UUID.randomUUID();
    private static final UUID OTHER_WS = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final FakeOpsStore store = new FakeOpsStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));
    private final OpsIncidentService service = new OpsIncidentService(store, clock);

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private static DerivedIncident derived(String key, Severity severity, String detail) {
        return new DerivedIncident(key, severity, "DEPENDENCY", "title " + key, "UNAVAILABLE", detail, "do something", "DEPENDENCY", null);
    }

    @Test
    void firstObservationOpensOneIncidentAndRepeatsDoNotDuplicate() {
        List<Incident> first = service.sync(WS, List.of(derived("DEPENDENCY:MINIO:UNAVAILABLE", Severity.CRITICAL, "a")));
        clock.advance(Duration.ofSeconds(30));
        List<Incident> second = service.sync(WS, List.of(derived("DEPENDENCY:MINIO:UNAVAILABLE", Severity.CRITICAL, "a")));
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).id()).isEqualTo(first.get(0).id());
        assertThat(second.get(0).firstObservedAt()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
        assertThat(store.inserts).isEqualTo(1);
    }

    @Test
    void writesAreThrottledToOncePerRefreshInterval() {
        service.sync(WS, List.of(derived("K", Severity.WARNING, "n=1")));
        clock.advance(Duration.ofSeconds(10));
        service.sync(WS, List.of(derived("K", Severity.WARNING, "n=2")));
        assertThat(store.updates).isZero();
        clock.advance(OpsIncidentService.REFRESH_INTERVAL);
        service.sync(WS, List.of(derived("K", Severity.WARNING, "n=3")));
        assertThat(store.updates).isEqualTo(1);
        assertThat(store.activeIncidents(WS).get(0).detail()).isEqualTo("n=3");
    }

    @Test
    void severityChangeIsAppliedImmediately() {
        service.sync(WS, List.of(derived("JOBS:BACKLOG", Severity.WARNING, "a")));
        clock.advance(Duration.ofSeconds(5));
        List<Incident> result = service.sync(WS, List.of(derived("JOBS:BACKLOG", Severity.CRITICAL, "a")));
        assertThat(result.get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(store.inserts).isEqualTo(1);
    }

    @Test
    void conditionThatDisappearsIsResolvedAutomaticallyAndStaysInHistory() {
        Incident opened = service.sync(WS, List.of(derived("K", Severity.WARNING, "a"))).get(0);
        clock.advance(Duration.ofMinutes(2));
        assertThat(service.sync(WS, List.of())).isEmpty();
        Incident resolved = store.incident(WS, opened.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isEqualTo(clock.instant());
        assertThat(service.list(WS, "RESOLVED", null, 0, 25).items()).hasSize(1);
    }

    @Test
    void reappearingConditionOpensANewIncident() {
        Incident first = service.sync(WS, List.of(derived("K", Severity.WARNING, "a"))).get(0);
        clock.advance(Duration.ofMinutes(1));
        service.sync(WS, List.of());
        clock.advance(Duration.ofMinutes(1));
        Incident second = service.sync(WS, List.of(derived("K", Severity.WARNING, "a"))).get(0);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.firstObservedAt()).isEqualTo(clock.instant());
        assertThat(service.list(WS, null, null, 0, 25).total()).isEqualTo(2);
    }

    @Test
    void acknowledgementIsRecordedOnceAndMeansSeenNotResolved() {
        Incident incident = service.sync(WS, List.of(derived("K", Severity.CRITICAL, "a"))).get(0);
        Incident acked = service.acknowledge(WS, USER, incident.id());
        assertThat(acked.acknowledged()).isTrue();
        assertThat(acked.status()).isEqualTo(IncidentStatus.ACTIVE);
        assertThat(store.acknowledgedBy.get(incident.id())).isEqualTo(USER);
        clock.advance(Duration.ofMinutes(5));
        Incident again = service.acknowledge(WS, UUID.randomUUID(), incident.id());
        assertThat(again.acknowledgedAt()).isEqualTo(acked.acknowledgedAt());
        assertThat(store.acknowledgedBy.get(incident.id())).isEqualTo(USER);
        // still counts as active until the condition clears
        assertThat(service.sync(WS, List.of(derived("K", Severity.CRITICAL, "a")))).hasSize(1);
        assertThat(OpsRules.overall(store.activeIncidents(WS))).isEqualTo(OverallStatus.ACTION_REQUIRED);
    }

    @Test
    void acknowledgementSurvivesRefreshes() {
        Incident incident = service.sync(WS, List.of(derived("K", Severity.WARNING, "a"))).get(0);
        service.acknowledge(WS, USER, incident.id());
        clock.advance(Duration.ofMinutes(10));
        Incident refreshed = service.sync(WS, List.of(derived("K", Severity.WARNING, "b"))).get(0);
        assertThat(refreshed.acknowledged()).isTrue();
        assertThat(refreshed.detail()).isEqualTo("b");
    }

    @Test
    void acknowledgingResolvedUnknownOrForeignIncidentsIsRejected() {
        Incident incident = service.sync(WS, List.of(derived("K", Severity.WARNING, "a"))).get(0);
        assertThatThrownBy(() -> service.acknowledge(WS, USER, UUID.randomUUID())).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.acknowledge(OTHER_WS, USER, incident.id())).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        service.sync(WS, List.of());
        assertThatThrownBy(() -> service.acknowledge(WS, USER, incident.id())).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void workspacesDoNotShareIncidents() {
        service.sync(WS, List.of(derived("K", Severity.WARNING, "a")));
        assertThat(service.sync(OTHER_WS, List.of())).isEmpty();
        assertThat(service.list(OTHER_WS, null, null, 0, 25).total()).isZero();
        assertThat(service.list(WS, null, null, 0, 25).total()).isEqualTo(1);
    }

    @Test
    void listFiltersByStatusAndSeverity() {
        service.sync(WS, List.of(derived("A", Severity.CRITICAL, "a"), derived("B", Severity.WARNING, "b")));
        assertThat(service.list(WS, "ACTIVE", Severity.CRITICAL, 0, 25).items()).extracting(Incident::key).containsExactly("A");
        assertThat(service.list(WS, "RESOLVED", null, 0, 25).items()).isEmpty();
        assertThat(service.list(WS, null, null, 0, 25).items()).extracting(Incident::key).containsExactly("A", "B");
    }
}
