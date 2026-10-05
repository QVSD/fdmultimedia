package com.fdmultimedia.api.opscontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fdmultimedia.api.opscontrol.OpsModels.*;
import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import com.fdmultimedia.api.workers.WorkerProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OpsIncidentReconcilerTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FakeOpsStore store = new FakeOpsStore();
    private final OpsProperties properties = new OpsProperties();
    private final SchedulerOperationTracker tracker = new SchedulerOperationTracker(clock);
    private DependencyHealthService dependencies;

    private static final class NoopTransactions implements PlatformTransactionManager {
        public TransactionStatus getTransaction(TransactionDefinition d) { return new SimpleTransactionStatus(); }
        public void commit(TransactionStatus s) {}
        public void rollback(TransactionStatus s) {}
    }

    private OpsIncidentReconciler reconciler() {
        dependencies = new DependencyHealthService(List.of(), properties, Clock.systemUTC());
        var snapshots = new OpsSnapshotService(store, dependencies, properties, new WorkerProperties(), tracker,
                new SchedulerCadencePolicy(new MockEnvironment()), clock);
        return new OpsIncidentReconciler(store, snapshots, new OpsIncidentService(store, clock), properties, tracker,
                new TransactionTemplate(new NoopTransactions()));
    }

    @AfterEach
    void tearDown() { if (dependencies != null) dependencies.shutdown(); }

    @Test
    void tickRecordsIncidentsForEveryWorkspaceAndReportsThroughTheTracker() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.workspaces = List.of(a, b);
        store.publishingFacts = new OpsFacts.PublishingFacts(0, 0, 0, 0, 0, 0, 1, null);
        reconciler().reconcile();
        assertThat(store.activeIncidents(a)).extracting(Incident::key).contains("PUBLISHING:OUTCOME_UNKNOWN");
        assertThat(store.activeIncidents(b)).extracting(Incident::key).contains("PUBLISHING:OUTCOME_UNKNOWN");
        var row = tracker.snapshots().stream().filter(s -> s.name().equals(SchedulerOperationTracker.OPERATIONS_INCIDENTS)).findFirst().orElseThrow();
        assertThat(row.state()).isEqualTo("SUCCEEDED");
        assertThat(row.processedCount()).isEqualTo(2);
    }

    @Test
    void anotherReplicaHoldingTheLockMakesThisTickANoop() {
        store.workspaces = List.of(UUID.randomUUID());
        store.publishingFacts = new OpsFacts.PublishingFacts(0, 0, 0, 0, 0, 0, 1, null);
        store.lockAvailable = false;
        OpsIncidentReconciler r = reconciler();
        r.reconcile();
        assertThat(store.inserts).isZero();
        assertThat(r.skippedTicks()).isEqualTo(1);
    }

    @Test
    void repeatedTicksAreIdempotent() {
        UUID ws = UUID.randomUUID();
        store.workspaces = List.of(ws);
        store.publishingFacts = new OpsFacts.PublishingFacts(0, 0, 0, 0, 0, 0, 1, null);
        OpsIncidentReconciler r = reconciler();
        r.reconcile();
        int afterFirst = store.inserts;
        r.reconcile();
        r.reconcile();
        assertThat(afterFirst).isGreaterThanOrEqualTo(1);
        assertThat(store.inserts).isEqualTo(afterFirst);
        assertThat(store.activeIncidents(ws)).extracting(Incident::key).doesNotHaveDuplicates().contains("PUBLISHING:OUTCOME_UNKNOWN");
    }

    @Test
    void aFailingTickIsRecordedByTheTrackerAndNeverEscapesAsAnException() {
        store.workspaces = List.of(UUID.randomUUID());
        OpsIncidentReconciler r = reconciler();
        store.failOnWorkspaceListing = true;
        r.reconcile();
        var row = tracker.snapshots().stream().filter(s -> s.name().equals(SchedulerOperationTracker.OPERATIONS_INCIDENTS)).findFirst().orElseThrow();
        assertThat(row.state()).isEqualTo("FAILED");
        assertThat(row.lastFailureCode()).isEqualTo("IllegalStateException");
        store.failOnWorkspaceListing = false;
        r.reconcile();
        assertThat(tracker.snapshots().stream().filter(s -> s.name().equals(SchedulerOperationTracker.OPERATIONS_INCIDENTS)).findFirst().orElseThrow().state())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void workspaceBatchIsBounded() {
        assertThat(OpsIncidentReconciler.WORKSPACE_LIMIT).isEqualTo(500);
    }
}
