package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps incidents current when nobody is looking at the page. One replica per tick wins a transaction-scoped advisory lock; the others
 * skip. Each tick covers at most {@value #WORKSPACE_LIMIT} workspaces and then runs two bounded housekeeping deletes. Reads also
 * reconcile on demand, so this is a repair path and a source of first-observation times, not the only writer.
 */
@Component
public class OpsIncidentReconciler {
    public static final int WORKSPACE_LIMIT = 500;
    static final String LOCK_KEY = "operations-incidents";
    private static final Logger log = LoggerFactory.getLogger(OpsIncidentReconciler.class);

    private final OpsStore store;
    private final OpsSnapshotService snapshots;
    private final OpsIncidentService incidents;
    private final OpsProperties properties;
    private final SchedulerOperationTracker operations;
    private final TransactionTemplate transactions;
    private final AtomicLong skippedTicks = new AtomicLong();

    public OpsIncidentReconciler(OpsStore store, OpsSnapshotService snapshots, OpsIncidentService incidents, OpsProperties properties,
            SchedulerOperationTracker operations, TransactionTemplate transactions) {
        this.store = store; this.snapshots = snapshots; this.incidents = incidents; this.properties = properties;
        this.operations = operations; this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${app.operations.incident-interval-ms:30000}",
            initialDelayString = "${app.operations.incident-initial-delay-ms:20000}")
    public void reconcile() {
        operations.run(SchedulerOperationTracker.OPERATIONS_INCIDENTS, this::tick);
    }

    private SchedulerOperationTracker.Outcome tick() {
        SchedulerOperationTracker.Outcome outcome = transactions.execute(status -> {
            if (!store.tryAdvisoryLock(LOCK_KEY)) {
                skippedTicks.incrementAndGet();
                return SchedulerOperationTracker.Outcome.NONE;
            }
            List<UUID> workspaces = store.workspaceIds(WORKSPACE_LIMIT);
            long active = 0;
            for (UUID workspaceId : workspaces) {
                active += incidents.sync(workspaceId, snapshots.snapshot(workspaceId).derived()).size();
            }
            int purged = incidents.purge(properties.getIncidentResolvedRetention(), properties.getSchedulerStatusRetention());
            if (purged > 0) log.info("Operations housekeeping removed {} expired row(s)", purged);
            return new SchedulerOperationTracker.Outcome(workspaces.size(), active);
        });
        return outcome == null ? SchedulerOperationTracker.Outcome.NONE : outcome;
    }

    public long skippedTicks() { return skippedTicks.get(); }
}
