package com.fdmultimedia.api.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SchedulingObservabilityServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");
    private static final UUID WORKER_ID = UUID.randomUUID();

    private final AuthService auth = mock(AuthService.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final JobExecutionMetricRepository metrics = mock(JobExecutionMetricRepository.class);
    private final SchedulingDecisionRepository decisions = mock(SchedulingDecisionRepository.class);
    private final SchedulingObservabilityService service = new SchedulingObservabilityService(
            auth, jobs, metrics, decisions, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Workspace workspace = new Workspace("Workspace", "workspace");
    private final AppUser user = new AppUser("owner@example.com", "hash", "Owner");
    private final AuthenticatedUser principal = new AuthenticatedUser(user);

    @BeforeEach
    void setUp() {
        when(auth.currentMembershipFor(principal))
                .thenReturn(new WorkspaceMembership(workspace, user, WorkspaceRole.OWNER));
        when(jobs.schedulingQueueSnapshot(eq(workspace.getId()), any())).thenReturn(queue());
        when(metrics.aggregateByJobType(eq(workspace.getId()), any())).thenReturn(List.of(jobMetric()));
        when(decisions.counts(eq(workspace.getId()), any())).thenReturn(counts());
    }

    @Test
    void defaultsToBoundedTwentyFourHourWorkspaceWindow() {
        SchedulingOverview overview = service.overview(principal, null);

        assertThat(overview.window()).isEqualTo("24h");
        assertThat(overview.queue().queued()).isEqualTo(2);
        assertThat(overview.queue().oldestQueuedAgeMs()).isEqualTo(3_600_000);
        assertThat(overview.execution()).singleElement().extracting(SchedulingOverview.JobTypeMetrics::jobType)
                .isEqualTo("SYSTEM_TEST");
        assertThat(overview.scheduling().fallbackClaims()).isEqualTo(1);
        verify(jobs).schedulingQueueSnapshot(workspace.getId(), NOW.minusSeconds(86_400));
    }

    @Test
    void acceptsControlledWindowAndRejectsArbitraryWindow() {
        service.overview(principal, "7d");
        verify(jobs).schedulingQueueSnapshot(workspace.getId(), NOW.minusSeconds(604_800));

        assertThatThrownBy(() -> service.overview(principal, "90d"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void workersScopesAggregateQueryToCallersWorkspaceOnly() {
        when(metrics.aggregateByWorkerAndJobType(eq(workspace.getId()), any())).thenReturn(List.of(workerMetric()));

        List<WorkerPerformanceSummary> result = service.workers(principal, "1h");

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.workerId()).isEqualTo(WORKER_ID);
            assertThat(summary.jobType()).isEqualTo("SYSTEM_TEST");
            assertThat(summary.averageExecutionMs()).isEqualTo(200d);
        });
        verify(metrics).aggregateByWorkerAndJobType(workspace.getId(), NOW.minusSeconds(3_600));
    }

    @Test
    void decisionsClampsLimitToBoundedRangeAndScopesToWorkspace() {
        when(decisions.recent(eq(workspace.getId()), any(), any())).thenReturn(List.of());

        service.decisions(principal, "24h", 500);
        verify(decisions).recent(
                org.mockito.ArgumentMatchers.eq(workspace.getId()),
                org.mockito.ArgumentMatchers.eq(NOW.minusSeconds(86_400)),
                org.mockito.ArgumentMatchers.argThat(page -> page.getPageSize() == 100));

        service.decisions(principal, "24h", -5);
        verify(decisions).recent(
                org.mockito.ArgumentMatchers.eq(workspace.getId()),
                org.mockito.ArgumentMatchers.eq(NOW.minusSeconds(86_400)),
                org.mockito.ArgumentMatchers.argThat(page -> page.getPageSize() == 1));
    }

    @Test
    void differentWorkspacesNeverShareQueueOrDecisionData() {
        Workspace otherWorkspace = new Workspace("Other", "other");
        AppUser otherUser = new AppUser("other@example.com", "hash", "Other");
        AuthenticatedUser otherPrincipal = new AuthenticatedUser(otherUser);
        when(auth.currentMembershipFor(otherPrincipal))
                .thenReturn(new WorkspaceMembership(otherWorkspace, otherUser, WorkspaceRole.OWNER));
        when(jobs.schedulingQueueSnapshot(eq(otherWorkspace.getId()), any())).thenReturn(queue());
        when(metrics.aggregateByJobType(eq(otherWorkspace.getId()), any())).thenReturn(List.of());
        when(decisions.counts(eq(otherWorkspace.getId()), any())).thenReturn(counts());

        service.overview(principal, "24h");
        service.overview(otherPrincipal, "24h");

        verify(jobs).schedulingQueueSnapshot(eq(workspace.getId()), any());
        verify(jobs).schedulingQueueSnapshot(eq(otherWorkspace.getId()), any());
        assertThat(workspace.getId()).isNotEqualTo(otherWorkspace.getId());
    }

    private QueueSnapshotView queue() {
        return new QueueSnapshotView() {
            public long getQueued() { return 2; }
            public long getAssigned() { return 1; }
            public long getRunning() { return 1; }
            public long getSucceeded() { return 4; }
            public long getFailed() { return 1; }
            public Instant getOldestQueuedAt() { return NOW.minusSeconds(3600); }
        };
    }

    private JobTypeMetricView jobMetric() {
        return new JobTypeMetricView() {
            public String getJobType() { return "SYSTEM_TEST"; }
            public long getAttempts() { return 5; }
            public long getSuccesses() { return 4; }
            public long getFailures() { return 1; }
            public Double getAverageQueueWaitMs() { return 100d; }
            public Double getAverageExecutionMs() { return 200d; }
            public Double getAverageTotalLatencyMs() { return 300d; }
        };
    }

    private WorkerJobTypeMetricView workerMetric() {
        return new WorkerJobTypeMetricView() {
            public UUID getWorkerId() { return WORKER_ID; }
            public String getWorkerName() { return "Worker A"; }
            public Instant getMostRecentExecutionAt() { return NOW; }
            public String getJobType() { return "SYSTEM_TEST"; }
            public long getAttempts() { return 5; }
            public long getSuccesses() { return 4; }
            public long getFailures() { return 1; }
            public Double getAverageQueueWaitMs() { return 100d; }
            public Double getAverageExecutionMs() { return 200d; }
            public Double getAverageTotalLatencyMs() { return 300d; }
        };
    }

    private SchedulingCountsView counts() {
        return new SchedulingCountsView() {
            public long getClaims() { return 5; }
            public long getFallbackClaims() { return 1; }
            public long getStarvationOverrideClaims() { return 0; }
        };
    }
}
