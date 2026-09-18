package com.fdmultimedia.api.jobs;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.highlights.HighlightAnalysisRepository;
import com.fdmultimedia.api.highlights.HighlightAnalysisStatus;
import com.fdmultimedia.api.publishing.PublicationRepository;
import com.fdmultimedia.api.publishing.PublicationStatus;
import com.fdmultimedia.api.transcripts.MediaTranscriptRepository;
import com.fdmultimedia.api.transcripts.TranscriptStatus;
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
    private final HighlightAnalysisRepository highlightAnalyses;
    private final MediaTranscriptRepository transcripts;
    private final PublicationRepository publications;
    private final WorkerRepository workers;
    private final WorkerCredentialRepository credentials;
    private final WorkerStatusService workerStatusService;
    private final WorkerEligibilityService eligibilityService;
    private final WorkerSchedulingService schedulingService;
    private final JobExecutionMetricService executionMetrics;
    private final SchedulingDecisionRepository schedulingDecisions;
    private final JobProperties properties;
    private final WorkerSchedulingProperties schedulingProperties;
    private final Clock clock;

    public JobService(
            AuthService authService,
            JobRepository jobs,
            MediaAssetRepository assets,
            HighlightAnalysisRepository highlightAnalyses,
            MediaTranscriptRepository transcripts,
            PublicationRepository publications,
            WorkerRepository workers,
            WorkerCredentialRepository credentials,
            WorkerStatusService workerStatusService,
            WorkerEligibilityService eligibilityService,
            WorkerSchedulingService schedulingService,
            JobExecutionMetricService executionMetrics,
            SchedulingDecisionRepository schedulingDecisions,
            JobProperties properties,
            WorkerSchedulingProperties schedulingProperties,
            Clock clock) {
        this.authService = authService;
        this.jobs = jobs;
        this.assets = assets;
        this.highlightAnalyses = highlightAnalyses;
        this.transcripts = transcripts;
        this.publications = publications;
        this.workers = workers;
        this.credentials = credentials;
        this.workerStatusService = workerStatusService;
        this.eligibilityService = eligibilityService;
        this.schedulingService = schedulingService;
        this.executionMetrics = executionMetrics;
        this.schedulingDecisions = schedulingDecisions;
        this.properties = properties;
        this.schedulingProperties = schedulingProperties;
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
        WorkerEligibility eligibility = eligibilityService.eligibleCapabilities(request);
        Instant now = Instant.now(clock);
        recoverExpiredLeases(worker.getWorkspace(), now);
        List<Job> candidates = jobs.findQueuedCandidatesForUpdate(
                        worker.getWorkspace().getId(),
                        eligibility.supportedJobTypes(),
                        eligibility.supportedHighlightAnalyzers(),
                        eligibility.supportedPublishingProviders(),
                        schedulingProperties.safeCandidateLimit());
        JobSchedulingDecision decision = schedulingService.selectJob(worker, candidates, now);
        return decision.job()
                .map(job -> {
                    job.claim(worker, now, now.plus(properties.getLeaseDuration()));
                    schedulingDecisions.save(new SchedulingDecision(
                            job, worker, decision, schedulingProperties.getPolicyId(), now));
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
            Instant now = Instant.now(clock);
            completeOwnedJob(job, worker, sanitizeResult(request.result()), now);
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
            Instant now = Instant.now(clock);
            failOwnedJob(
                    job,
                    worker,
                    request.errorCode(),
                    safeErrorMessage(request.errorMessage()),
                    Boolean.TRUE.equals(request.terminal()),
                    now);
            return toSummary(job);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    public void completeOwnedJob(Job job, Worker worker, Map<String, Object> result, Instant now) {
        job.complete(worker, sanitizeResult(result), now);
        executionMetrics.record(job, worker, JobExecutionOutcome.SUCCEEDED, now);
    }

    public void failOwnedJob(
            Job job,
            Worker worker,
            String errorCode,
            String errorMessage,
            boolean terminal,
            Instant now) {
        JobExecutionSnapshot snapshot = JobExecutionSnapshot.from(job);
        if (terminal) {
            job.failTerminal(worker, errorCode, safeErrorMessage(errorMessage), now);
            executionMetrics.record(job, snapshot, JobExecutionOutcome.FAILED, now);
            return;
        }
        job.fail(worker, errorCode, safeErrorMessage(errorMessage), now);
        JobExecutionOutcome outcome = job.getStatus() == JobStatus.FAILED
                ? JobExecutionOutcome.FAILED
                : JobExecutionOutcome.RETRYABLE_FAILED;
        executionMetrics.record(job, snapshot, outcome, now);
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
                    JobExecutionSnapshot snapshot = JobExecutionSnapshot.from(job);
                    job.recoverExpiredLease(now);
                    executionMetrics.record(job, snapshot, JobExecutionOutcome.LEASE_EXPIRED, now);
                    reconcileRecoveredImportAsset(job, now);
                    reconcileRecoveredInspectionAsset(job, now);
                    reconcileRecoveredProcessingAsset(job, now);
                    reconcileRecoveredHighlightAnalysis(job, now);
                    reconcileRecoveredTranscription(job, now);
                    reconcileRecoveredPublication(job, now);
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

    private void reconcileRecoveredProcessingAsset(Job job, Instant now) {
        if (job.getType() != JobType.CREATE_CLIP && job.getType() != JobType.CREATE_SOCIAL_VERTICAL) {
            return;
        }
        assets.findByProcessingJobId(job.getId()).ifPresent(asset -> {
            if (asset.getStatus() == MediaAssetStatus.READY || asset.getStatus() == MediaAssetStatus.FAILED) {
                return;
            }
            if (job.getStatus() == JobStatus.FAILED) {
                asset.markFailed(job.getErrorCode(), job.getErrorMessage(), now);
            } else if (job.getStatus() == JobStatus.QUEUED) {
                asset.markProcessingPendingForRetry(now);
            }
        });
    }

    private void reconcileRecoveredHighlightAnalysis(Job job, Instant now) {
        if (job.getType() != JobType.ANALYZE_HIGHLIGHTS) {
            return;
        }
        highlightAnalyses.findByAnalysisJobId(job.getId()).ifPresent(analysis -> {
            if (analysis.getStatus() == HighlightAnalysisStatus.SUCCEEDED || analysis.getStatus() == HighlightAnalysisStatus.FAILED) {
                return;
            }
            if (job.getStatus() == JobStatus.FAILED) {
                analysis.markFailed(job.getErrorCode(), job.getErrorMessage(), now);
            } else if (job.getStatus() == JobStatus.QUEUED) {
                analysis.markPendingForRetry(now);
            }
        });
    }

    private void reconcileRecoveredTranscription(Job job, Instant now) {
        if (job.getType() != JobType.TRANSCRIBE_MEDIA) {
            return;
        }
        transcripts.findByTranscriptionJobId(job.getId()).ifPresent(transcript -> {
            if (transcript.getStatus() == TranscriptStatus.SUCCEEDED || transcript.getStatus() == TranscriptStatus.FAILED) {
                return;
            }
            if (job.getStatus() == JobStatus.FAILED) {
                transcript.markFailed(job.getErrorCode(), job.getErrorMessage(), now);
            } else if (job.getStatus() == JobStatus.QUEUED) {
                transcript.markPendingForRetry(now);
            }
        });
    }

    private void reconcileRecoveredPublication(Job job, Instant now) {
        if (job.getType() != JobType.PUBLISH_MEDIA) {
            return;
        }
        publications.findByJobId(job.getId()).ifPresent(publication -> {
            if (publication.getStatus() == PublicationStatus.PUBLISHED || publication.getStatus() == PublicationStatus.FAILED) {
                return;
            }
            if (job.getStatus() == JobStatus.FAILED) {
                publication.markFailed(job.getErrorCode(), job.getErrorMessage(), now);
            } else if (job.getStatus() == JobStatus.QUEUED) {
                publication.markPendingForRetry(now);
            }
        });
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
        if (type == JobType.CREATE_CLIP) {
            return validateCreateClipPayload(payload);
        }
        if (type == JobType.CREATE_SOCIAL_VERTICAL) {
            return validateDerivativePayload(payload);
        }
        if (type == JobType.ANALYZE_HIGHLIGHTS) {
            return validateHighlightAnalysisPayload(payload);
        }
        if (type == JobType.TRANSCRIBE_MEDIA) {
            return validateTranscriptionPayload(payload);
        }
        if (type == JobType.PUBLISH_MEDIA) {
            return validatePublishMediaPayload(payload);
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

    private Map<String, Object> validateHighlightAnalysisPayload(Map<String, Object> payload) {
        String assetId = uuidString(payload.get("assetId"), "assetId");
        String analyzerType = stringValue(payload.getOrDefault("analyzerType", "DETERMINISTIC_V1"), "analyzerType");
        if (analyzerType.isBlank() || analyzerType.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "analyzerType is invalid");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("assetId", assetId);
        normalized.put("analyzerType", analyzerType);
        Object transcriptId = payload.get("transcriptId");
        if (transcriptId != null) {
            normalized.put("transcriptId", uuidString(transcriptId, "transcriptId"));
        }
        return normalized;
    }

    private Map<String, Object> validateCreateClipPayload(Map<String, Object> payload) {
        String sourceAssetId = uuidString(payload.get("sourceAssetId"), "sourceAssetId");
        String outputAssetId = uuidString(payload.get("outputAssetId"), "outputAssetId");
        long startMs = longValue(payload.get("startMs"), "startMs");
        long durationMs = longValue(payload.get("durationMs"), "durationMs");
        if (startMs < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startMs must be non-negative");
        }
        if (durationMs <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "durationMs must be positive");
        }
        try {
            Math.addExact(startMs, durationMs);
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clip timing is too large");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("sourceAssetId", sourceAssetId);
        normalized.put("outputAssetId", outputAssetId);
        normalized.put("startMs", startMs);
        normalized.put("durationMs", durationMs);
        return normalized;
    }

    private Map<String, Object> validateDerivativePayload(Map<String, Object> payload) {
        String sourceAssetId = uuidString(payload.get("sourceAssetId"), "sourceAssetId");
        String outputAssetId = uuidString(payload.get("outputAssetId"), "outputAssetId");
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("sourceAssetId", sourceAssetId);
        normalized.put("outputAssetId", outputAssetId);
        return normalized;
    }

    private Map<String, Object> validateTranscriptionPayload(Map<String, Object> payload) {
        String assetId = uuidString(payload.get("assetId"), "assetId");
        String provider = stringValue(payload.get("provider"), "provider");
        String model = stringValue(payload.get("model"), "model");
        if (provider.isBlank() || provider.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider is invalid");
        }
        if (model.isBlank() || model.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model is invalid");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("assetId", assetId);
        normalized.put("provider", provider);
        normalized.put("model", model);
        return normalized;
    }

    private Map<String, Object> validatePublishMediaPayload(Map<String, Object> payload) {
        String publicationId = uuidString(payload.get("publicationId"), "publicationId");
        String assetId = uuidString(payload.get("assetId"), "assetId");
        String socialAccountId = uuidString(payload.get("socialAccountId"), "socialAccountId");
        String provider = stringValue(payload.getOrDefault("provider", "TEST"), "provider");
        if (provider.isBlank() || provider.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider is invalid");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("publicationId", publicationId);
        normalized.put("assetId", assetId);
        normalized.put("socialAccountId", socialAccountId);
        normalized.put("provider", provider);
        return normalized;
    }

    private String uuidString(Object value, String field) {
        String text = stringValue(value, field);
        try {
            UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a UUID");
        }
        return text;
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
