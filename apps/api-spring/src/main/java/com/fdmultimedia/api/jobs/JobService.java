package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
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
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobService {

    private final AuthService authService;
    private final JobRepository jobs;
    private final WorkerRepository workers;
    private final WorkerCredentialRepository credentials;
    private final WorkerStatusService workerStatusService;
    private final JobProperties properties;
    private final Clock clock;

    public JobService(
            AuthService authService,
            JobRepository jobs,
            WorkerRepository workers,
            WorkerCredentialRepository credentials,
            WorkerStatusService workerStatusService,
            JobProperties properties,
            Clock clock) {
        this.authService = authService;
        this.jobs = jobs;
        this.workers = workers;
        this.credentials = credentials;
        this.workerStatusService = workerStatusService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public JobSummary create(AuthenticatedUser principal, JobCreateRequest request) {
        Workspace workspace = currentWorkspace(principal);
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
        return jobs.findByWorkspaceAndId(workspace, jobId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
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
        Instant now = Instant.now(clock);
        recoverExpiredLeases(worker.getWorkspace(), now);
        return jobs.findNextQueuedForUpdate(worker.getWorkspace().getId())
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
            job.fail(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), Instant.now(clock));
            return toSummary(job);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    private void recoverExpiredLeases(Workspace workspace, Instant now) {
        jobs.findExpiredLeasesForUpdate(workspace.getId(), now)
                .forEach(job -> job.recoverExpiredLease(now));
    }

    private Worker requireOnlineWorker(WorkerPrincipal principal, String machineIdentifier) {
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

    private Job requireJobForWorkerWorkspace(Worker worker, UUID jobId) {
        return jobs.findByWorkspaceAndId(worker.getWorkspace(), jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        return membership.getWorkspace();
    }

    private Map<String, Object> validatePayload(JobType type, Map<String, Object> payload) {
        if (type != JobType.SYSTEM_TEST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported job type");
        }
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payload is required");
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
