package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SchedulingObservabilityService {

    private static final Map<String, Duration> WINDOWS = Map.of(
            "1h", Duration.ofHours(1),
            "24h", Duration.ofHours(24),
            "7d", Duration.ofDays(7),
            "30d", Duration.ofDays(30));

    private final AuthService authService;
    private final JobRepository jobs;
    private final JobExecutionMetricRepository executionMetrics;
    private final SchedulingDecisionRepository decisions;
    private final Clock clock;

    public SchedulingObservabilityService(
            AuthService authService,
            JobRepository jobs,
            JobExecutionMetricRepository executionMetrics,
            SchedulingDecisionRepository decisions,
            Clock clock) {
        this.authService = authService;
        this.jobs = jobs;
        this.executionMetrics = executionMetrics;
        this.decisions = decisions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SchedulingOverview overview(AuthenticatedUser principal, String requestedWindow) {
        Window window = window(requestedWindow);
        Workspace workspace = workspace(principal);
        Instant now = Instant.now(clock);
        Instant since = now.minus(window.duration());
        QueueSnapshotView queue = jobs.schedulingQueueSnapshot(workspace.getId(), since);
        SchedulingCountsView counts = decisions.counts(workspace.getId(), since);
        Long oldestAge = queue.getOldestQueuedAt() == null
                ? null
                : Math.max(0, Duration.between(queue.getOldestQueuedAt(), now).toMillis());
        return new SchedulingOverview(
                window.id(),
                new SchedulingOverview.QueueMetrics(
                        queue.getQueued(), queue.getAssigned(), queue.getRunning(), queue.getSucceeded(), queue.getFailed(), oldestAge),
                executionMetrics.aggregateByJobType(workspace.getId(), since).stream()
                        .map(this::toJobTypeMetrics)
                        .toList(),
                new SchedulingOverview.SchedulingMetrics(
                        counts.getClaims(), counts.getFallbackClaims(), counts.getStarvationOverrideClaims()));
    }

    @Transactional(readOnly = true)
    public List<WorkerPerformanceSummary> workers(AuthenticatedUser principal, String requestedWindow) {
        Window window = window(requestedWindow);
        Workspace workspace = workspace(principal);
        return executionMetrics.aggregateByWorkerAndJobType(
                        workspace.getId(), Instant.now(clock).minus(window.duration())).stream()
                .map(row -> new WorkerPerformanceSummary(
                        row.getWorkerId(), row.getWorkerName(), row.getJobType(), row.getAttempts(), row.getSuccesses(),
                        row.getFailures(), row.getAverageQueueWaitMs(), row.getAverageExecutionMs(),
                        row.getAverageTotalLatencyMs(), row.getMostRecentExecutionAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SchedulingDecisionSummary> decisions(
            AuthenticatedUser principal, String requestedWindow, int requestedLimit) {
        Window window = window(requestedWindow);
        Workspace workspace = workspace(principal);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return decisions.recent(
                workspace.getId(), Instant.now(clock).minus(window.duration()), PageRequest.of(0, limit));
    }

    private SchedulingOverview.JobTypeMetrics toJobTypeMetrics(JobTypeMetricView row) {
        return new SchedulingOverview.JobTypeMetrics(
                row.getJobType(), row.getAttempts(), row.getSuccesses(), row.getFailures(),
                row.getAverageQueueWaitMs(), row.getAverageExecutionMs(), row.getAverageTotalLatencyMs());
    }

    private Workspace workspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private Window window(String requested) {
        String id = requested == null || requested.isBlank() ? "24h" : requested;
        Duration duration = WINDOWS.get(id);
        if (duration == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported scheduling window");
        }
        return new Window(id, duration);
    }

    private record Window(String id, Duration duration) {
    }
}
