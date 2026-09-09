package com.fdmultimedia.api.workers;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkerService {

    private final AuthService authService;
    private final WorkerRepository workers;
    private final WorkerCredentialRepository credentials;
    private final WorkerStatusService statusService;
    private final WorkerProperties properties;
    private final Clock clock;

    public WorkerService(
            AuthService authService,
            WorkerRepository workers,
            WorkerCredentialRepository credentials,
            WorkerStatusService statusService,
            WorkerProperties properties,
            Clock clock) {
        this.authService = authService;
        this.workers = workers;
        this.credentials = credentials;
        this.statusService = statusService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public WorkerRegistrationResponse register(WorkerPrincipal principal, WorkerRegistrationRequest request) {
        WorkerCredential credential = requireCredential(principal);
        Workspace workspace = credential.getWorkspace();
        Instant now = Instant.now(clock);
        Worker worker = workers.findByWorkspaceAndMachineIdentifier(workspace, request.machineIdentifier().trim())
                .map(existing -> {
                    if (!existing.getCredential().getId().equals(credential.getId())) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Worker belongs to another credential");
                    }
                    existing.updateFrom(request, now);
                    return existing;
                })
                .orElseGet(() -> workers.save(new Worker(workspace, credential, request, now)));
        return registrationResponse(worker, now);
    }

    @Transactional
    public WorkerHeartbeatResponse heartbeat(WorkerPrincipal principal, WorkerHeartbeatRequest request) {
        WorkerCredential credential = requireCredential(principal);
        Worker worker = workers.findByWorkspaceAndMachineIdentifier(
                        credential.getWorkspace(),
                        request.machineIdentifier().trim())
                .filter(existing -> existing.getCredential().getId().equals(credential.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Worker is not registered"));
        Instant now = Instant.now(clock);
        worker.heartbeat(now);
        return new WorkerHeartbeatResponse(
                worker.getId(),
                statusService.statusFor(worker.getLastSeenAt()),
                now,
                properties.getHeartbeatInterval().toSeconds(),
                properties.getOfflineThreshold().toSeconds());
    }

    @Transactional(readOnly = true)
    public List<WorkerSummary> listFor(AuthenticatedUser principal) {
        Workspace workspace = currentWorkspace(principal);
        return workers.findByWorkspaceOrderByNameAsc(workspace).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkerSummary getFor(AuthenticatedUser principal, UUID workerId) {
        Workspace workspace = currentWorkspace(principal);
        return workers.findByWorkspaceAndId(workspace, workerId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker not found"));
    }

    private WorkerCredential requireCredential(WorkerPrincipal principal) {
        return credentials.findById(principal.credentialId())
                .filter(WorkerCredential::isEnabled)
                .filter(credential -> credential.getWorkspace().getId().equals(principal.workspaceId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Worker credential disabled"));
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        return membership.getWorkspace();
    }

    private WorkerRegistrationResponse registrationResponse(Worker worker, Instant now) {
        return new WorkerRegistrationResponse(
                worker.getId(),
                statusService.statusFor(worker.getLastSeenAt()),
                now,
                properties.getHeartbeatInterval().toSeconds(),
                properties.getOfflineThreshold().toSeconds());
    }

    private WorkerSummary toSummary(Worker worker) {
        return new WorkerSummary(
                worker.getId(),
                worker.getName(),
                statusService.statusFor(worker.getLastSeenAt()),
                worker.getMachineIdentifier(),
                worker.getOperatingSystem(),
                worker.getArchitecture(),
                worker.getCpuModel(),
                worker.getCpuLogicalCores(),
                worker.getTotalMemoryBytes(),
                worker.getGpuModel(),
                worker.getGpuMemoryBytes(),
                worker.getAgentVersion(),
                worker.getLastSeenAt(),
                worker.getRegisteredAt());
    }
}
