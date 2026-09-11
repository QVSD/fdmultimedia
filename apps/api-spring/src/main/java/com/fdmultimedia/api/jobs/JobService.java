package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerCredentialRepository;
import com.fdmultimedia.api.workers.WorkerRepository;
import com.fdmultimedia.api.workers.WorkerStatus;
import com.fdmultimedia.api.workers.WorkerStatusService;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobService {

    private final AuthService authService;
    private final JobRepository jobs;
    private final MediaAssetRepository assets;
    private final WorkerRepository workers;
    private final WorkerCredentialRepository credentials;
    private final WorkerStatusService workerStatusService;
    private final JobProperties properties;
    private final Clock clock;

    public JobService(
            AuthService authService,
            JobRepository jobs,
            MediaAssetRepository assets,
            WorkerRepository workers,
            WorkerCredentialRepository credentials,
            WorkerStatusService workerStatusService,
            JobProperties properties,
            Clock clock) {
        this.authService = authService;
        this.jobs = jobs;
        this.assets = assets;
        this.workers = workers;
        this.credentials = credentials;
        this.workerStatusService = workerStatusService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public JobSummary create(AuthenticatedUser principal, JobCreateRequest request) {
        Workspace workspace = currentWorkspace(principal);
        return createForWorkspace(workspace, request);
    }

    @Transactional
    public JobSummary createForWorkspace(Workspace workspace, JobCreateRequest request) {
        Map<String, Object> payload = validatePayload(request.type(), request.payload());
        Instant now = Instant.now(clock);
        Job job = jobs.save(new Job(
                workspace,
                request.type(),
                payload,
                properties.getDefaultMaxAttempts(),
                now));
        return toSummary(job);
    }

    @Transactional(readOnly = true)
    public List<JobSummary> listFor(AuthenticatedUser principal) {
        Workspace workspace = currentWorkspace(principal);
        return jobs.findByWorkspaceOrderByQueuedAtDesc(workspace).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public JobSummary getFor(AuthenticatedUser principal, UUID jobId) {
        Workspace workspace = currentWorkspace(principal);
        return getJobEntityForWorkspace(workspace, jobId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    public Optional<Job> getJobEntityForWorkspace(Workspace workspace, UUID jobId) {
        return jobs.findByWorkspaceAndId(workspace, jobId);
    }

    @Transactional
    public JobSummary cancel(AuthenticatedUser principal, UUID jobId) {
        Workspace workspace = currentWorkspace(principal);
        Job job = jobs.findByWorkspaceAndId(workspace, jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
        try {
            job.cancel(Instant.now(clock));
            return toSummary(job);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @Transactional
    public WorkerJobClaimResponse claim(WorkerPrincipal principal, WorkerJobClaimRequest request) {
        Worker worker = requireOnlineWorker(principal, request.machineIdentifier());
        List<String> supportedTypes = supportedTypeNames(request);
        Instant now = Instant.now(clock);
        recoverExpiredLeases(worker.getWorkspace(), now);
        return jobs.findNextQueuedForUpdate(worker.getWorkspace().getId(), supportedTypes)
                .map(job -> {
                    job.claim(worker, now, now.plus(properties.getLeaseDuration()));
                    return new WorkerJobClaimResponse(
                            true,
                            job.getId(),
                            job.getType(),
                            job.getPayload(),
                            job.getAttemptCount(),
                            properties.getLeaseDuration().toSeconds());
                })
                .orElseGet(WorkerJobClaimResponse::none);
    }

    @Transactional
    public JobSummary started(WorkerPrincipal principal, UUID jobId, WorkerJobUpdateRequest request) {
        Worker worker = requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireJobForWorkerWorkspace(worker, jobId);
        try {
            Instant now = Instant.now(clock);
            job.start(worker, now, now.plus(properties.getLeaseDuration()));
            return toSummary(job);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @Transactional
    public JobSummary complete(WorkerPrincipal principal, UUID jobId, WorkerJobUpdateRequest request) {
        Worker worker = requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireJobForWorkerWorkspace(worker, jobId);
        try {
            job.complete(worker, sanitizeResult(request.result()), Instant.now(clock));
            return toSummary(job);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @Transactional
    public JobSummary fail(WorkerPrincipal principal, UUID jobId, WorkerJobUpdateRequest request) {
        Worker worker = requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireJobForWorkerWorkspace(worker, jobId);
        try {
            if (Boolean.TRUE.equals(request.terminal())) {
                job.failTerminal(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), Instant.now(clock));
            } else {
                job.fail(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), Instant.now(clock));
            }
            return toSummary(job);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @Transactional
    public JobSummary renew(WorkerPrincipal principal, UUID jobId, WorkerJobUpdateRequest request) {
        Worker worker = requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireJobForWorkerWorkspace(worker, jobId);
        try {
            Instant now = Instant.now(clock);
            job.renewLease(worker, now, now.plus(properties.getLeaseDuration()));
            return toSummary(job);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    private void recoverExpiredLeases(Workspace workspace, Instant now) {
        jobs.findExpiredLeasesForUpdate(workspace.getId(), now)
                .forEach(job -> {
                    job.recoverExpiredLease(now);
                    reconcileRecoveredImportAsset(job, now);
                    reconcileRecoveredInspectionAsset(job, now);
                });
    }

    private void reconcileRecoveredImportAsset(Job job, Instant now) {
        if (job.getType() != JobType.IMPORT_MEDIA) {
            return;
        }
        assets.findByImportJobId(job.getId()).ifPresent(asset -> reconcileRecoveredImportAsset(job, asset, now));
    }

    private void reconcileRecoveredImportAsset(Job job, MediaAsset asset, Instant now) {
        if (asset.getStatus() == MediaAssetStatus.READY || asset.getStatus() == MediaAssetStatus.FAILED) {
            return;
        }
        if (job.getStatus() == JobStatus.FAILED) {
            asset.markFailed(job.getErrorCode(), job.getErrorMessage(), now);
        } else if (job.getStatus() == JobStatus.QUEUED) {
            asset.markPendingForRetry(now);
        }
    }

    private void reconcileRecoveredInspectionAsset(Job job, Instant now) {
        if (job.getType() != JobType.INSPECT_MEDIA) {
            return;
        }
        assets.findByInspectionJobId(job.getId()).ifPresent(asset -> {
            if (job.getStatus() == JobStatus.FAILED) {
                asset.markInspectionFailed(job.getErrorCode(), job.getErrorMessage(), now);
            } else if (job.getStatus() == JobStatus.QUEUED) {
                asset.markInspectionPendingForRetry(now);
            }
        });
    }

    private List<String> supportedTypeNames(WorkerJobClaimRequest request) {
        if (request.supportedJobTypes() == null || request.supportedJobTypes().isEmpty()) {
            return List.of(JobType.SYSTEM_TEST.name());
        }
        List<String> supported = request.supportedJobTypes().stream()
                .filter(type -> type != null)
                .map(Enum::name)
                .distinct()
                .toList();
        return supported.isEmpty() ? List.of(JobType.SYSTEM_TEST.name()) : supported;
    }

    public Worker requireOnlineWorker(WorkerPrincipal principal, String machineIdentifier) {
        WorkerCredential credential = credentials.findById(principal.credentialId())
                .filter(WorkerCredential::isEnabled)
                .filter(workerCredential -> workerCredential.getWorkspace().getId().equals(principal.workspaceId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Worker credential disabled"));
        Worker worker = workers.findByWorkspaceAndMachineIdentifier(
                        credential.getWorkspace(),
                        machineIdentifier.trim())
                .filter(candidate -> candidate.getCredential().getId().equals(credential.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Worker is not registered"));
        if (workerStatusService.statusFor(worker.getLastSeenAt()) != WorkerStatus.ONLINE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Worker is offline");
        }
        return worker;
    }

    public Job requireJobForWorkerWorkspace(Worker worker, UUID jobId) {
        return jobs.findByWorkspaceAndIdForUpdate(worker.getWorkspace(), jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        return membership.getWorkspace();
    }

    private Map<String, Object> validatePayload(JobType type, Map<String, Object> payload) {
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payload is required");
        }
        if (type == JobType.IMPORT_MEDIA) {
            return validateAssetReferencePayload(payload);
        }
        if (type == JobType.INSPECT_MEDIA) {
            return validateAssetReferencePayload(payload);
        }
        if (type != JobType.SYSTEM_TEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported job type");
        }
        String message = stringValue(payload.get("message"), "message");
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        if (message.length() > properties.getMaxSystemTestMessageLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is too long");
        }
        long durationMs = longValue(payload.get("durationMs"), "durationMs");
        if (durationMs < 0 || durationMs > properties.getMaxSystemTestDurationMs()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "durationMs is outside the allowed range");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("message", message);
        normalized.put("durationMs", durationMs);
        return normalized;
    }

    private Map<String, Object> validateAssetReferencePayload(Map<String, Object> payload) {
        String assetId = stringValue(payload.get("assetId"), "assetId");
        try {
            UUID.fromString(assetId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assetId must be a UUID");
        }
        return Map.of("assetId", assetId);
    }

    private Map<String, Object> sanitizeResult(Map<String, Object> result) {
        if (result == null) {
            return Map.of();
        }
        return new LinkedHashMap<>(result);
    }

    private String safeErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Worker reported failure";
        }
        return errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
    }

    private String stringValue(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a string");
        }
        return text.trim();
    }

    private long longValue(Object value, String field) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a number");
    }

    private JobSummary toSummary(Job job) {
        Worker assignedWorker = job.getAssignedWorker();
        return new JobSummary(
                job.getId(),
                job.getType(),
                job.getStatus(),
                job.getPayload(),
                job.getResult(),
                job.getErrorCode(),
                job.getErrorMessage(),
                assignedWorker == null ? null : assignedWorker.getId(),
                assignedWorker == null ? null : assignedWorker.getName(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getQueuedAt(),
                job.getAssignedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getLeaseExpiresAt(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
