package com.fdmultimedia.api.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WorkerServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final WorkerRepository workers = mock(WorkerRepository.class);
    private final WorkerCredentialRepository credentials = mock(WorkerCredentialRepository.class);
    private final WorkerProperties properties = new WorkerProperties();
    private final WorkerStatusService statusService =
            new WorkerStatusService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    private final WorkerService service = new WorkerService(
            authService,
            workers,
            credentials,
            statusService,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private WorkerCredential credential;
    private WorkerPrincipal principal;

    @BeforeEach
    void setUp() {
        properties.setHeartbeatInterval(Duration.ofSeconds(10));
        properties.setOfflineThreshold(Duration.ofSeconds(30));
        workspace = new Workspace("FD Multimedia", "fd-multimedia");
        UUID credentialId = UUID.randomUUID();
        credential = new WorkerCredential(credentialId, workspace, "local-agent", "$2a$10$hash");
        principal = new WorkerPrincipal(credentialId, workspace.getId(), "local-agent");
        when(credentials.findById(credentialId)).thenReturn(Optional.of(credential));
    }

    @Test
    void registersNewWorkerForCredentialWorkspace() {
        when(workers.findByWorkspaceAndMachineIdentifier(workspace, "machine-1")).thenReturn(Optional.empty());
        when(workers.save(any(Worker.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerRegistrationResponse response = service.register(principal, registration("machine-1", "Node A"));

        ArgumentCaptor<Worker> captor = ArgumentCaptor.forClass(Worker.class);
        verify(workers).save(captor.capture());
        Worker saved = captor.getValue();
        assertThat(saved.getWorkspace()).isSameAs(workspace);
        assertThat(saved.getCredential()).isSameAs(credential);
        assertThat(saved.getMachineIdentifier()).isEqualTo("machine-1");
        assertThat(saved.getLastSeenAt()).isEqualTo(NOW);
        assertThat(response.status()).isEqualTo(WorkerStatus.ONLINE);
        assertThat(response.heartbeatIntervalSeconds()).isEqualTo(10);
    }

    @Test
    void registrationIsIdempotentForSameWorkspaceMachineAndCredential() {
        Worker existing = new Worker(workspace, credential, registration("machine-1", "Node A"), NOW.minusSeconds(60));
        when(workers.findByWorkspaceAndMachineIdentifier(workspace, "machine-1")).thenReturn(Optional.of(existing));

        WorkerRegistrationResponse response = service.register(principal, registration("machine-1", "Renamed Node"));

        verify(workers, never()).save(any());
        assertThat(existing.getName()).isEqualTo("Renamed Node");
        assertThat(existing.getLastSeenAt()).isEqualTo(NOW);
        assertThat(response.workerId()).isEqualTo(existing.getId());
    }

    @Test
    void rejectsRegistrationWhenMachineBelongsToDifferentCredential() {
        WorkerCredential otherCredential =
                new WorkerCredential(UUID.randomUUID(), workspace, "other-agent", "$2a$10$hash");
        Worker existing = new Worker(workspace, otherCredential, registration("machine-1", "Node A"), NOW);
        when(workers.findByWorkspaceAndMachineIdentifier(workspace, "machine-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.register(principal, registration("machine-1", "Node A")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void heartbeatUpdatesOnlyRegisteredWorkerForCredential() {
        Worker existing = new Worker(workspace, credential, registration("machine-1", "Node A"), NOW.minusSeconds(20));
        when(workers.findByWorkspaceAndMachineIdentifier(workspace, "machine-1")).thenReturn(Optional.of(existing));

        WorkerHeartbeatResponse response = service.heartbeat(principal, new WorkerHeartbeatRequest("machine-1"));

        assertThat(existing.getLastSeenAt()).isEqualTo(NOW);
        assertThat(response.status()).isEqualTo(WorkerStatus.ONLINE);
        assertThat(existing.getLastTelemetryAt()).isNull();
    }

    @Test
    void heartbeatAcceptsTelemetryAndCapabilities() {
        Worker existing = new Worker(workspace, credential, registration("machine-1", "Node A"), NOW.minusSeconds(20));
        when(workers.findByWorkspaceAndMachineIdentifier(workspace, "machine-1")).thenReturn(Optional.of(existing));

        WorkerHeartbeatResponse response = service.heartbeat(
                principal,
                new WorkerHeartbeatRequest(
                        "machine-1",
                        new WorkerTelemetryRequest(0.25, 0.10, 8_589_934_592L, 134_217_728L, 536_870_912L, 1),
                        List.of(JobType.SYSTEM_TEST, JobType.CREATE_CLIP),
                        List.of("DETERMINISTIC_V1", "TRANSCRIPT_SEMANTIC_V1")));

        assertThat(response.status()).isEqualTo(WorkerStatus.ONLINE);
        assertThat(existing.getCurrentSupportedJobTypes()).containsExactly("SYSTEM_TEST", "CREATE_CLIP");
        assertThat(existing.getCurrentSupportedHighlightAnalyzers()).containsExactly("DETERMINISTIC_V1", "TRANSCRIPT_SEMANTIC_V1");
        assertThat(existing.getSystemCpuLoad()).isEqualTo(0.25);
        assertThat(existing.getActiveJobs()).isEqualTo(1);
        assertThat(existing.getLastTelemetryAt()).isEqualTo(NOW);
    }

    @Test
    void heartbeatSanitizesInvalidTelemetryWithoutRejectingHeartbeat() {
        Worker existing = new Worker(workspace, credential, registration("machine-1", "Node A"), NOW.minusSeconds(20));
        when(workers.findByWorkspaceAndMachineIdentifier(workspace, "machine-1")).thenReturn(Optional.of(existing));

        service.heartbeat(
                principal,
                new WorkerHeartbeatRequest(
                        "machine-1",
                        new WorkerTelemetryRequest(2.0, Double.NaN, existing.getTotalMemoryBytes() + 1, -1L, 1024L, -3),
                        List.of(JobType.SYSTEM_TEST),
                        List.of("DETERMINISTIC_V1")));

        assertThat(existing.getLastSeenAt()).isEqualTo(NOW);
        assertThat(existing.getSystemCpuLoad()).isNull();
        assertThat(existing.getProcessCpuLoad()).isNull();
        assertThat(existing.getAvailableMemoryBytes()).isNull();
        assertThat(existing.getJvmHeapUsedBytes()).isNull();
        assertThat(existing.getJvmHeapMaxBytes()).isEqualTo(1024L);
        assertThat(existing.getActiveJobs()).isNull();
    }

    @Test
    void rejectsHeartbeatForUnregisteredWorker() {
        when(workers.findByWorkspaceAndMachineIdentifier(workspace, "unknown-machine")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.heartbeat(principal, new WorkerHeartbeatRequest("unknown-machine")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listsOnlyWorkersFromCurrentWorkspaceWithDerivedStatus() {
        AppUser user = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        AuthenticatedUser principalUser = new AuthenticatedUser(user);
        when(authService.currentMembershipFor(principalUser))
                .thenReturn(new WorkspaceMembership(workspace, user, WorkspaceRole.OWNER));
        Worker online = new Worker(workspace, credential, registration("machine-1", "Node A"), NOW.minusSeconds(5));
        Worker offline = new Worker(workspace, credential, registration("machine-2", "Node B"), NOW.minusSeconds(45));
        when(workers.findByWorkspaceOrderByNameAsc(workspace)).thenReturn(List.of(online, offline));

        List<WorkerSummary> result = service.listFor(principalUser);

        assertThat(result).extracting(WorkerSummary::name).containsExactly("Node A", "Node B");
        assertThat(result).extracting(WorkerSummary::status).containsExactly(WorkerStatus.ONLINE, WorkerStatus.OFFLINE);
    }

    @Test
    void staleTelemetryIsNotReturnedAsCurrent() {
        AppUser user = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        AuthenticatedUser principalUser = new AuthenticatedUser(user);
        when(authService.currentMembershipFor(principalUser))
                .thenReturn(new WorkspaceMembership(workspace, user, WorkspaceRole.OWNER));
        Worker stale = new Worker(workspace, credential, registration("machine-1", "Node A"), NOW.minusSeconds(5));
        stale.heartbeat(
                NOW.minusSeconds(120),
                new WorkerTelemetryRequest(0.5, 0.2, 1024L, 512L, 2048L, 1),
                List.of("SYSTEM_TEST"),
                List.of("DETERMINISTIC_V1"));
        when(workers.findByWorkspaceOrderByNameAsc(workspace)).thenReturn(List.of(stale));

        WorkerSummary summary = service.listFor(principalUser).getFirst();

        assertThat(summary.telemetry().fresh()).isFalse();
        assertThat(summary.telemetry().systemCpuLoad()).isNull();
        assertThat(summary.telemetry().lastTelemetryAt()).isEqualTo(NOW.minusSeconds(120));
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
