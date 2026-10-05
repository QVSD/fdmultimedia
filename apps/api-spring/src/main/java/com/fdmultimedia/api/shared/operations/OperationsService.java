package com.fdmultimedia.api.shared.operations;

import com.fdmultimedia.api.assets.ObjectStorageService;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.jobs.JobRepository;
import com.fdmultimedia.api.jobs.OperationalJobCountsView;
import com.fdmultimedia.api.workers.WorkerRepository;
import com.fdmultimedia.api.workers.WorkerStatus;
import com.fdmultimedia.api.workers.WorkerStatusService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsService {
    private final AuthService auth;
    private final WorkerRepository workers;
    private final WorkerStatusService workerStatus;
    private final JobRepository jobs;
    private final SchedulerOperationTracker schedulers;
    private final ObjectStorageService storage;
    private final ApiInstanceIdentity instance;
    private final Clock clock;

    public OperationsService(AuthService auth, WorkerRepository workers, WorkerStatusService workerStatus,
            JobRepository jobs, SchedulerOperationTracker schedulers, ObjectStorageService storage,
            ApiInstanceIdentity instance, Clock clock) {
        this.auth = auth; this.workers = workers; this.workerStatus = workerStatus; this.jobs = jobs;
        this.schedulers = schedulers; this.storage = storage; this.instance = instance; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Status status(AuthenticatedUser principal) {
        var workspace = auth.currentMembershipFor(principal).getWorkspace();
        var workerRows = workers.findByWorkspaceOrderByNameAsc(workspace);
        long online = workerRows.stream().filter(row -> workerStatus.statusFor(row.getLastSeenAt()) == WorkerStatus.ONLINE).count();
        OperationalJobCountsView counts = jobs.operationalCounts(workspace.getId());
        return new Status(Instant.now(clock), instance.value(), workspace.getId(), "UP",
                storage.isAvailable() ? "UP" : "DOWN", "OPTIONAL_NOT_READINESS",
                "SPRING_SESSION_JDBC", schedulers.isAccepting() ? "RUNNING" : "STOPPING",
                new WorkerCounts(workerRows.size(), online, workerRows.size() - online),
                new JobCounts(counts.getQueued(), counts.getAssigned(), counts.getRunning(), counts.getSucceeded(),
                        counts.getFailed(), counts.getCancelled()),
                schedulers.snapshots());
    }

    public record Status(Instant observedAt, String apiInstance, UUID workspaceId, String database,
            String objectStorage, String rabbitMq, String sessionBackend, String lifecycle,
            WorkerCounts workers, JobCounts jobs, List<SchedulerOperationTracker.Snapshot> schedulers) {}
    public record WorkerCounts(long total, long online, long offline) {}
    public record JobCounts(long queued, long assigned, long running, long succeeded, long failed, long cancelled) {}
}
