package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.opscontrol.OpsFacts.*;
import com.fdmultimedia.api.opscontrol.OpsModels.*;
import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import com.fdmultimedia.api.workers.WorkerProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reads every authoritative source once and reduces it to sections plus the deterministic incident set. No writes. */
@Service
public class OpsSnapshotService {
    public record Snapshot(Instant observedAt, List<Dependency> dependencies, List<WorkerRow> workers, WorkersSection workersSection,
            JobsSection jobs, List<SchedulerRow> schedulers, SchedulersSection schedulersSection, PublishingSection publishing,
            List<ProviderRow> providers, AutomationSection automation, List<DerivedIncident> derived) {}

    private final OpsStore store;
    private final DependencyHealthService dependencies;
    private final OpsProperties properties;
    private final WorkerProperties workerProperties;
    private final SchedulerOperationTracker tracker;
    private final SchedulerCadencePolicy cadence;
    private final Clock clock;

    public OpsSnapshotService(OpsStore store, DependencyHealthService dependencies, OpsProperties properties,
            WorkerProperties workerProperties, SchedulerOperationTracker tracker, SchedulerCadencePolicy cadence, Clock clock) {
        this.store = store; this.dependencies = dependencies; this.properties = properties; this.workerProperties = workerProperties;
        this.tracker = tracker; this.cadence = cadence; this.clock = clock;
    }

    public Snapshot snapshot(UUID workspaceId) {
        Instant now = Instant.now(clock);
        Instant since = now.minus(properties.getRecentWindow());
        Instant burstSince = now.minus(properties.getFailureBurstWindow());
        List<Dependency> deps = dependencies.snapshot();

        var heartbeat = workerProperties.getHeartbeatInterval();
        var offline = workerProperties.getOfflineThreshold();
        List<WorkerRow> workers = OpsRules.workerRows(store.workers(workspaceId, since), now, heartbeat, offline);
        WorkersSection workersSection = OpsRules.workersSection(workers, now, properties.getOfflineWorkerIncidentWindow(), heartbeat, offline);

        JobFacts jobFacts = store.jobFacts(workspaceId, now, since, burstSince);
        JobsSection jobs = OpsRules.jobsSection(jobFacts, now, properties);

        List<SchedulerRow> schedulers = OpsRules.schedulerRows(tracker.snapshots(), store.schedulerInstances(), cadence::cadence,
                cadence::enabled, properties, now);
        SchedulersSection schedulersSection = OpsRules.schedulersSection(schedulers);

        PublishingFacts publishingFacts = store.publishingFacts(workspaceId, now, since, burstSince, now.minus(properties.getOverdueWarning()));
        PublishingSection publishing = OpsRules.publishingSection(publishingFacts, now, properties);
        List<ProviderRow> providers = OpsRules.providerRows(store.providers(workspaceId, since), properties);

        AutomationFacts automationFacts = store.automationFacts(workspaceId, now, since);
        AutomationSection automation = OpsRules.automationSection(automationFacts);

        List<DerivedIncident> derived = OpsRules.deriveIncidents(new OpsRules.Evidence(deps, workersSection, jobs, schedulers, publishing,
                providers, store.openRollbackRecommendations(workspaceId, OpsStore.MAX_ROLLBACK_INCIDENTS), jobFacts.failedBurst(),
                publishingFacts.failedBurst(), properties.getFailureBurst()));
        return new Snapshot(now, deps, workers, workersSection, jobs, schedulers, schedulersSection, publishing, providers, automation, derived);
    }
}
