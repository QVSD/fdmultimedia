package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.jobs.JobStatus;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.opscontrol.OpsModels.*;
import com.fdmultimedia.api.opscontrol.OpsSnapshotService.Snapshot;
import com.fdmultimedia.api.shared.operations.ApiInstanceIdentity;
import com.fdmultimedia.api.shared.operations.ReleaseInfo;
import com.fdmultimedia.api.shared.operations.SchedulerOperationTracker;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** The operator-facing read model. Every method reads authoritative state; none changes anything except incident bookkeeping. */
@Service
public class OperationsControlService {
    public static final long REFRESH_HINT_SECONDS = 15;
    private static final int TOP_INCIDENTS = 5;

    public record WorkersView(WorkersSection summary, Page<WorkerRow> workers) {}
    public record JobsView(JobsSection summary, Page<JobRow> jobs) {}
    public record SchedulersView(SchedulersSection summary, List<SchedulerRow> schedulers) {}
    public record PublishingView(PublishingSection summary, List<ProviderRow> providers, Page<PublicationRow> attention) {}
    public record IncidentsView(IncidentsSection summary, Page<Incident> incidents) {}

    private final OpsSnapshotService snapshots;
    private final OpsIncidentService incidents;
    private final OpsStore store;
    private final OpsProperties properties;
    private final SchedulerOperationTracker tracker;
    private final ApiInstanceIdentity instance;
    private final ReleaseInfo release;
    private final Clock clock;

    public OperationsControlService(OpsSnapshotService snapshots, OpsIncidentService incidents, OpsStore store, OpsProperties properties,
            SchedulerOperationTracker tracker, ApiInstanceIdentity instance, ReleaseInfo release, Clock clock) {
        this.snapshots = snapshots; this.incidents = incidents; this.store = store; this.properties = properties;
        this.tracker = tracker; this.instance = instance; this.release = release; this.clock = clock;
    }

    public Overview overview(UUID workspaceId) {
        Snapshot s = snapshots.snapshot(workspaceId);
        List<Incident> active = incidents.sync(workspaceId, s.derived());
        IncidentsSection incidentsSection = OpsRules.incidentsSection(active);
        Dependency postgres = s.dependencies().stream().filter(d -> d.name().equals("POSTGRES")).findFirst().orElse(null);
        boolean databaseUp = postgres == null || postgres.status() == ComponentStatus.HEALTHY;
        ApiSection api = new ApiSection(databaseUp && tracker.isAccepting() ? ComponentStatus.HEALTHY : ComponentStatus.DEGRADED,
                instance.value(), Math.max(0, ManagementFactory.getRuntimeMXBean().getUptime() / 1000), "UP", databaseUp ? "UP" : "DOWN",
                release.version(), release.commit(), release.builtAt());
        List<Incident> top = active.stream().sorted(OpsRules.INCIDENT_ORDER).limit(TOP_INCIDENTS).toList();
        return new Overview(OpsModels.OVERVIEW_ENGINE, s.observedAt(), OpsRules.overall(active), api, s.dependencies(), s.workersSection(),
                s.jobs(), s.schedulersSection(), s.publishing(), s.automation(), incidentsSection, top, REFRESH_HINT_SECONDS);
    }

    public WorkersView workers(UUID workspaceId, String state, int page, int size) {
        WorkerState filter = parse(WorkerState.class, state, "state");
        Snapshot s = snapshots.snapshot(workspaceId);
        List<WorkerRow> rows = s.workers().stream().filter(w -> filter == null || w.state() == filter).toList();
        return new WorkersView(s.workersSection(), slice(rows, page, size));
    }

    public JobsView jobs(UUID workspaceId, String status, String type, int page, int size) {
        String statusFilter = status == null || status.isBlank() ? null : parse(JobStatus.class, status, "status").name();
        String typeFilter = type == null || type.isBlank() ? null : parse(JobType.class, type, "type").name();
        Snapshot s = snapshots.snapshot(workspaceId);
        return new JobsView(s.jobs(), store.jobs(workspaceId, statusFilter, typeFilter, page, size));
    }

    public JobDetail job(UUID workspaceId, UUID jobId) {
        return store.job(workspaceId, jobId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    public SchedulersView schedulers(UUID workspaceId) {
        Snapshot s = snapshots.snapshot(workspaceId);
        return new SchedulersView(s.schedulersSection(), s.schedulers());
    }

    public PublishingView publishing(UUID workspaceId, int page, int size) {
        Snapshot s = snapshots.snapshot(workspaceId);
        Instant since = Instant.now(clock).minus(properties.getRecentWindow());
        return new PublishingView(s.publishing(), s.providers(), store.publicationAttention(workspaceId, since, page, size));
    }

    public IncidentsView incidents(UUID workspaceId, String status, String severity, int page, int size) {
        String statusFilter = null;
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) statusFilter = parse(IncidentStatus.class, status, "status").name();
        Severity severityFilter = parse(Severity.class, severity, "severity");
        Snapshot s = snapshots.snapshot(workspaceId);
        List<Incident> active = incidents.sync(workspaceId, s.derived());
        return new IncidentsView(OpsRules.incidentsSection(active), incidents.list(workspaceId, statusFilter, severityFilter, page, size));
    }

    public Incident acknowledge(UUID workspaceId, UUID userId, UUID incidentId) {
        return incidents.acknowledge(workspaceId, userId, incidentId);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown " + field + "; expected one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    private static <T> Page<T> slice(List<T> all, int page, int size) {
        int p = Math.max(0, page), s = Math.max(1, Math.min(size, OpsStore.MAX_PAGE));
        int from = (int) Math.min((long) p * s, all.size());
        return new Page<>(all.subList(from, Math.min(from + s, all.size())), p, s, all.size());
    }

    static Comparator<Incident> order() { return OpsRules.INCIDENT_ORDER; }
}
