package com.fdmultimedia.api.workers;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkerService {

    private static final int MAX_REPORTED_ACTIVE_JOBS = 10_000;

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
        worker.heartbeat(
                now,
                sanitizeTelemetry(request.telemetry(), worker.getTotalMemoryBytes()),
                sanitizeJobTypes(request.supportedJobTypes()),
                sanitizeHighlightAnalyzers(request.supportedHighlightAnalyzers()));
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
                worker.getCurrentSupportedJobTypes(),
                worker.getCurrentSupportedHighlightAnalyzers(),
                telemetrySummary(worker),
                worker.getLastSeenAt(),
                worker.getRegisteredAt());
    }

    private WorkerTelemetryRequest sanitizeTelemetry(WorkerTelemetryRequest telemetry, long totalMemoryBytes) {
        if (telemetry == null) {
            return null;
        }
        Long availableMemoryBytes = sanitizeNonNegativeLong(telemetry.availableMemoryBytes());
        if (availableMemoryBytes != null && availableMemoryBytes > totalMemoryBytes) {
            availableMemoryBytes = null;
        }
        return new WorkerTelemetryRequest(
                sanitizeLoad(telemetry.systemCpuLoad()),
                sanitizeLoad(telemetry.processCpuLoad()),
                availableMemoryBytes,
                sanitizeNonNegativeLong(telemetry.jvmHeapUsedBytes()),
                sanitizeNonNegativeLong(telemetry.jvmHeapMaxBytes()),
                sanitizeActiveJobs(telemetry.activeJobs()));
    }

    private Double sanitizeLoad(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
            return null;
        }
        return value;
    }

    private Long sanitizeNonNegativeLong(Long value) {
        return value == null || value < 0 ? null : value;
    }

    private Integer sanitizeActiveJobs(Integer value) {
        if (value == null || value < 0 || value > MAX_REPORTED_ACTIVE_JOBS) {
            return null;
        }
        return value;
    }

    private List<String> sanitizeJobTypes(List<JobType> supportedJobTypes) {
        if (supportedJobTypes == null) {
            return null;
        }
        return supportedJobTypes.stream()
                .filter(type -> type != null)
                .map(Enum::name)
                .distinct()
                .toList();
    }

    private List<String> sanitizeHighlightAnalyzers(List<String> supportedHighlightAnalyzers) {
        if (supportedHighlightAnalyzers == null) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        for (String analyzer : supportedHighlightAnalyzers) {
            if (analyzer != null && !analyzer.isBlank()) {
                String value = analyzer.trim();
                if (value.length() <= 64 && !normalized.contains(value)) {
                    normalized.add(value);
                }
            }
        }
        return List.copyOf(normalized);
    }

    private WorkerTelemetrySummary telemetrySummary(Worker worker) {
        Instant lastTelemetryAt = worker.getLastTelemetryAt();
        boolean fresh = lastTelemetryAt != null
                && !lastTelemetryAt.isBefore(Instant.now(clock).minus(telemetryFreshnessWindow()));
        return new WorkerTelemetrySummary(
                fresh ? worker.getSystemCpuLoad() : null,
                fresh ? worker.getProcessCpuLoad() : null,
                fresh ? worker.getAvailableMemoryBytes() : null,
                fresh ? worker.getJvmHeapUsedBytes() : null,
                fresh ? worker.getJvmHeapMaxBytes() : null,
                fresh ? worker.getActiveJobs() : null,
                lastTelemetryAt,
                fresh);
    }

    private Duration telemetryFreshnessWindow() {
        return properties.getOfflineThreshold().multipliedBy(2);
    }
}
