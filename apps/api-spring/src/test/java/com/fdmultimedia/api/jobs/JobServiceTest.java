package com.fdmultimedia.api.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerCredentialRepository;
import com.fdmultimedia.api.workers.WorkerProperties;
import com.fdmultimedia.api.workers.WorkerRegistrationRequest;
import com.fdmultimedia.api.workers.WorkerRepository;
import com.fdmultimedia.api.workers.WorkerStatusService;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class JobServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T05:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final WorkerRepository workers = mock(WorkerRepository.class);
    private final WorkerCredentialRepository credentials = mock(WorkerCredentialRepository.class);
    private final JobProperties jobProperties = new JobProperties();
    private final WorkerProperties workerProperties = new WorkerProperties();
    private final WorkerStatusService workerStatusService =
            new WorkerStatusService(workerProperties, Clock.fixed(NOW, ZoneOffset.UTC));
    private final JobService service = new JobService(
            authService,
            jobs,
            workers,
            credentials,
            workerStatusService,
            jobProperties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private WorkerCredential credential;
    private Worker worker;
    private WorkerPrincipal workerPrincipal;

    @BeforeEach
    void setUp() {
        workerProperties.setOfflineThreshold(Duration.ofSeconds(30));
        jobProperties.setLeaseDuration(Duration.ofSeconds(20));
        jobProperties.setDefaultMaxAttempts(3);
        workspace = new Workspace("FD Multimedia", "fd-multimedia");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user))
                .thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        credential = new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash");
        worker = new Worker(workspace, credential, registration("machine-1", "Node A"), NOW.minusSeconds(5));
        workerPrincipal = new WorkerPrincipal(credential);
        when(credentials.findById(credential.getId())).thenReturn(Optional.of(credential));
        when(workers.findByWorkspaceAndMachineIdentifier(workspace, "machine-1")).thenReturn(Optional.of(worker));
    }

    @Test
    void createsSystemTestJobInCurrentWorkspace() {
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobSummary summary = service.create(user, createRequest(2_000));

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobs).save(captor.capture());
        Job saved = captor.getValue();
        assertThat(saved.getWorkspace()).isSameAs(workspace);
        assertThat(summary.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(summary.payload()).containsEntry("message", "hello worker");
        assertThat(summary.payload()).containsEntry("durationMs", 2_000L);
    }

    @Test
    void prePersistDoesNotOverwriteInjectedClockTimestamps() {
        Job job = job();

        job.prePersist();

        assertThat(job.getQueuedAt()).isEqualTo(NOW);
        assertThat(job.getCreatedAt()).isEqualTo(NOW);
        assertThat(job.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsExcessiveSystemTestDuration() {
        assertThatThrownBy(() -> service.create(user, createRequest(20_000)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createsImportMediaJobOnlyWithAssetReference() {
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UUID assetId = UUID.randomUUID();

        JobSummary summary = service.createForWorkspace(
                workspace,
                new JobCreateRequest(JobType.IMPORT_MEDIA, Map.of("assetId", assetId.toString())));

        assertThat(summary.type()).isEqualTo(JobType.IMPORT_MEDIA);
        assertThat(summary.payload()).containsEntry("assetId", assetId.toString());
        assertThatThrownBy(() -> service.createForWorkspace(workspace, new JobCreateRequest(JobType.IMPORT_MEDIA, Map.of())))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsAndGetsJobsOnlyFromCurrentWorkspace() {
        Job job = job();
        when(jobs.findByWorkspaceOrderByQueuedAtDesc(workspace)).thenReturn(List.of(job));
        when(jobs.findByWorkspaceAndId(workspace, job.getId())).thenReturn(Optional.of(job));

        assertThat(service.listFor(user)).hasSize(1);
        assertThat(service.getFor(user, job.getId()).id()).isEqualTo(job.getId());
    }

    @Test
    void workerClaimsNextQueuedJobAtomicallyThroughRepositoryLock() {
        Job job = job();
        when(jobs.findExpiredLeasesForUpdate(workspace.getId(), NOW)).thenReturn(List.of());
        when(jobs.findNextQueuedForUpdate(workspace.getId(), List.of("SYSTEM_TEST"))).thenReturn(Optional.of(job));

        WorkerJobClaimResponse response = service.claim(workerPrincipal, new WorkerJobClaimRequest("machine-1", null));

        assertThat(response.available()).isTrue();
        assertThat(response.jobId()).isEqualTo(job.getId());
        assertThat(job.getStatus()).isEqualTo(JobStatus.ASSIGNED);
        assertThat(job.getAssignedWorker()).isSameAs(worker);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(20));
        verify(jobs).findNextQueuedForUpdate(workspace.getId(), List.of("SYSTEM_TEST"));
    }

    @Test
    void claimReportsNoJobsAvailable() {
        when(jobs.findExpiredLeasesForUpdate(workspace.getId(), NOW)).thenReturn(List.of());
        when(jobs.findNextQueuedForUpdate(workspace.getId(), List.of("SYSTEM_TEST"))).thenReturn(Optional.empty());

        WorkerJobClaimResponse response = service.claim(workerPrincipal, new WorkerJobClaimRequest("machine-1", null));

        assertThat(response.available()).isFalse();
    }

    @Test
    void workerClaimFiltersBySupportedJobTypes() {
        Job job = new Job(
                workspace,
                JobType.IMPORT_MEDIA,
                Map.of("assetId", UUID.randomUUID().toString()),
                3,
                NOW);
        when(jobs.findExpiredLeasesForUpdate(workspace.getId(), NOW)).thenReturn(List.of());
        when(jobs.findNextQueuedForUpdate(workspace.getId(), List.of("SYSTEM_TEST", "IMPORT_MEDIA")))
                .thenReturn(Optional.of(job));

        WorkerJobClaimResponse response = service.claim(
                workerPrincipal,
                new WorkerJobClaimRequest("machine-1", List.of(JobType.SYSTEM_TEST, JobType.IMPORT_MEDIA)));

        assertThat(response.available()).isTrue();
        assertThat(response.type()).isEqualTo(JobType.IMPORT_MEDIA);
        verify(jobs).findNextQueuedForUpdate(workspace.getId(), List.of("SYSTEM_TEST", "IMPORT_MEDIA"));
    }

    @Test
    void legacyWorkersDefaultToSystemTestOnly() {
        when(jobs.findExpiredLeasesForUpdate(workspace.getId(), NOW)).thenReturn(List.of());
        when(jobs.findNextQueuedForUpdate(workspace.getId(), List.of("SYSTEM_TEST"))).thenReturn(Optional.empty());

        WorkerJobClaimResponse response = service.claim(
                workerPrincipal,
                new WorkerJobClaimRequest("machine-1", null));

        assertThat(response.available()).isFalse();
        verify(jobs).findNextQueuedForUpdate(workspace.getId(), List.of("SYSTEM_TEST"));
    }

    @Test
    void workerCannotUpdateAnotherWorkersJob() {
        Worker otherWorker = new Worker(workspace, credential, registration("machine-2", "Node B"), NOW.minusSeconds(5));
        Job job = job();
        job.claim(otherWorker, NOW, NOW.plusSeconds(20));
        when(jobs.findByWorkspaceAndId(workspace, job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.started(workerPrincipal, job.getId(), updateRequest(null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void successfulExecutionCompletesJob() {
        Job job = job();
        job.claim(worker, NOW.minusSeconds(1), NOW.plusSeconds(20));
        when(jobs.findByWorkspaceAndId(workspace, job.getId())).thenReturn(Optional.of(job));

        service.started(workerPrincipal, job.getId(), updateRequest(null));
        JobSummary summary = service.complete(workerPrincipal, job.getId(), updateRequest(Map.of("message", "done")));

        assertThat(summary.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.result()).containsEntry("message", "done");
        assertThat(summary.leaseExpiresAt()).isNull();
    }

    @Test
    void assignedWorkerCanRenewActiveJobLease() {
        Job job = job();
        job.claim(worker, NOW.minusSeconds(2), NOW.plusSeconds(5));
        job.start(worker, NOW.minusSeconds(1), NOW.plusSeconds(5));
        when(jobs.findByWorkspaceAndId(workspace, job.getId())).thenReturn(Optional.of(job));

        JobSummary summary = service.renew(workerPrincipal, job.getId(), updateRequest(null));

        assertThat(summary.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(summary.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void failureRequeuesUntilMaxAttemptsThenFails() {
        Job retryable = job();
        retryable.claim(worker, NOW.minusSeconds(2), NOW.plusSeconds(20));
        retryable.start(worker, NOW.minusSeconds(1), NOW.plusSeconds(20));
        when(jobs.findByWorkspaceAndId(workspace, retryable.getId())).thenReturn(Optional.of(retryable));

        JobSummary firstFailure = service.fail(workerPrincipal, retryable.getId(), failureRequest("Synthetic failure"));

        assertThat(firstFailure.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(firstFailure.attemptCount()).isEqualTo(1);

        Job exhausted = job();
        exhausted.claim(worker, NOW.minusSeconds(5), NOW.plusSeconds(20));
        exhausted.fail(worker, "ERR", "one", NOW.minusSeconds(4));
        exhausted.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(20));
        exhausted.fail(worker, "ERR", "two", NOW.minusSeconds(2));
        exhausted.claim(worker, NOW.minusSeconds(1), NOW.plusSeconds(20));
        exhausted.start(worker, NOW.minusMillis(500), NOW.plusSeconds(20));
        when(jobs.findByWorkspaceAndId(workspace, exhausted.getId())).thenReturn(Optional.of(exhausted));

        JobSummary finalFailure = service.fail(workerPrincipal, exhausted.getId(), failureRequest("final"));

        assertThat(finalFailure.status()).isEqualTo(JobStatus.FAILED);
        assertThat(finalFailure.attemptCount()).isEqualTo(3);
    }

    @Test
    void expiredLeaseRecoversBeforeClaim() {
        Job expired = job();
        expired.claim(worker, NOW.minusSeconds(60), NOW.minusSeconds(30));
        when(jobs.findExpiredLeasesForUpdate(workspace.getId(), NOW)).thenReturn(List.of(expired));
        when(jobs.findNextQueuedForUpdate(workspace.getId(), List.of("SYSTEM_TEST"))).thenReturn(Optional.empty());

        service.claim(workerPrincipal, new WorkerJobClaimRequest("machine-1", null));

        assertThat(expired.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(expired.getErrorCode()).isEqualTo("LEASE_EXPIRED");
    }

    @Test
    void terminalJobCannotBeExecutedAgain() {
        Job job = job();
        job.claim(worker, NOW.minusSeconds(2), NOW.plusSeconds(20));
        job.start(worker, NOW.minusSeconds(1), NOW.plusSeconds(20));
        job.complete(worker, Map.of("ok", true), NOW);
        when(jobs.findByWorkspaceAndId(workspace, job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.complete(workerPrincipal, job.getId(), updateRequest(Map.of("again", true))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void offlineWorkerCannotClaim() {
        Worker offlineWorker = new Worker(workspace, credential, registration("machine-1", "Node A"), NOW.minusSeconds(45));
        when(workers.findByWorkspaceAndMachineIdentifier(workspace, "machine-1")).thenReturn(Optional.of(offlineWorker));

        assertThatThrownBy(() -> service.claim(workerPrincipal, new WorkerJobClaimRequest("machine-1", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
        verify(jobs, never()).findNextQueuedForUpdate(any(), any());
    }

    private Job job() {
        return new Job(workspace, JobType.SYSTEM_TEST, Map.of("message", "hello worker", "durationMs", 1L), 3, NOW);
    }

    private JobCreateRequest createRequest(long durationMs) {
        return new JobCreateRequest(JobType.SYSTEM_TEST, Map.of("message", "hello worker", "durationMs", durationMs));
    }

    private WorkerJobUpdateRequest updateRequest(Map<String, Object> result) {
        return new WorkerJobUpdateRequest("machine-1", result, null, null, null);
    }

    private WorkerJobUpdateRequest failureRequest(String message) {
        return new WorkerJobUpdateRequest("machine-1", null, "SYSTEM_TEST_FAILED", message, null);
    }

    private WorkerRegistrationRequest registration(String machineIdentifier, String name) {
        return new WorkerRegistrationRequest(
                machineIdentifier,
                name,
                "Windows 11",
                "amd64",
                "AMD Ryzen",
                16,
                34_359_738_368L,
                null,
                null,
                "fdm-worker/0.1.0");
    }
}
