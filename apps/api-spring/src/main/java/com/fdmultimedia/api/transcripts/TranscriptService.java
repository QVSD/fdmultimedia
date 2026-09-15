package com.fdmultimedia.api.transcripts;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.assets.MediaInspectionStatus;
import com.fdmultimedia.api.assets.MediaProperties;
import com.fdmultimedia.api.assets.ObjectStorageService;
import com.fdmultimedia.api.assets.StorageAccess;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobCreateRequest;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.jobs.JobStatus;
import com.fdmultimedia.api.jobs.JobSummary;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TranscriptService {

    private static final List<TranscriptStatus> ACTIVE_STATUSES = List.of(TranscriptStatus.PENDING, TranscriptStatus.RUNNING);

    private final AuthService authService;
    private final MediaAssetRepository assets;
    private final MediaTranscriptRepository transcripts;
    private final TranscriptSegmentRepository segments;
    private final JobService jobService;
    private final ObjectStorageService storage;
    private final MediaProperties mediaProperties;
    private final TranscriptProperties transcriptProperties;
    private final Clock clock;

    public TranscriptService(
            AuthService authService,
            MediaAssetRepository assets,
            MediaTranscriptRepository transcripts,
            TranscriptSegmentRepository segments,
            JobService jobService,
            ObjectStorageService storage,
            MediaProperties mediaProperties,
            TranscriptProperties transcriptProperties,
            Clock clock) {
        this.authService = authService;
        this.assets = assets;
        this.transcripts = transcripts;
        this.segments = segments;
        this.jobService = jobService;
        this.storage = storage;
        this.mediaProperties = mediaProperties;
        this.transcriptProperties = transcriptProperties;
        this.clock = clock;
    }

    @Transactional
    public MediaTranscriptSummary createTranscript(AuthenticatedUser principal, UUID assetId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        validateSourceForTranscription(asset);
        String provider = normalize(transcriptProperties.getProvider(), "LOCAL_WHISPER_CLI");
        String model = normalize(transcriptProperties.getModel(), "local");
        return transcripts.findFirstByWorkspaceAndAssetAndProviderAndModelAndStatusInOrderByCreatedAtDesc(
                        workspace, asset, provider, model, ACTIVE_STATUSES)
                .map(this::toSummary)
                .orElseGet(() -> createTranscript(workspace, asset, provider, model));
    }

    @Transactional(readOnly = true)
    public List<MediaTranscriptSummary> listForAsset(AuthenticatedUser principal, UUID assetId) {
        Workspace workspace = currentWorkspace(principal);
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        return transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, asset).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public MediaTranscriptSummary getFor(AuthenticatedUser principal, UUID transcriptId) {
        Workspace workspace = currentWorkspace(principal);
        return transcripts.findByWorkspaceAndId(workspace, transcriptId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transcript not found"));
    }

    @Transactional
    public WorkerTranscriptAuthorizationResponse authorizeWorkerTranscription(
            WorkerPrincipal principal,
            UUID jobId,
            String machineIdentifier) {
        Worker worker = jobService.requireOnlineWorker(principal, machineIdentifier);
        Job job = requireTranscriptionJob(worker, jobId);
        MediaTranscript transcript = requireTranscriptForJob(job);
        MediaAsset asset = transcript.getAsset();
        validateJobReferencesTranscript(job, transcript, asset);
        validateSourceForTranscription(asset);
        if (asset.getStorageKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not stored");
        }
        Instant now = Instant.now(clock);
        transcript.markRunning(now);
        StorageAccess access = storage.presignedGet(asset.getStorageKey());
        return new WorkerTranscriptAuthorizationResponse(
                transcript.getId(),
                asset.getId(),
                access.url(),
                mediaProperties.getMaxDownloadSizeBytes(),
                (int) mediaProperties.getConnectTimeout().toSeconds(),
                (int) mediaProperties.getReadTimeout().toSeconds(),
                asset.getDurationMs(),
                transcript.getProvider(),
                transcript.getModel(),
                transcriptProperties.getMaxSegments(),
                transcriptProperties.getMaxSegmentTextLength(),
                transcriptProperties.getMaxTotalTextLength());
    }

    @Transactional
    public MediaTranscriptSummary completeWorkerTranscription(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerTranscriptCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireTranscriptionJob(worker, jobId);
        MediaTranscript transcript = requireTranscriptForJob(job);
        MediaAsset asset = transcript.getAsset();
        if (!transcript.getId().equals(request.transcriptId()) || !asset.getId().equals(request.assetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transcript does not match job");
        }
        validateJobReferencesTranscript(job, transcript, asset);
        validateSourceForTranscription(asset);
        Instant now = Instant.now(clock);
        List<SegmentValue> values = validateSegments(asset, request.segments());
        segments.deleteByTranscript(transcript);
        List<TranscriptSegment> rows = values.stream()
                .map(value -> new TranscriptSegment(
                        transcript,
                        value.sequence(),
                        value.startMs(),
                        value.endMs(),
                        value.text(),
                        value.confidence(),
                        now))
                .toList();
        segments.saveAll(rows);
        transcript.markSucceeded(safeShort(request.detectedLanguage(), 32), request.durationMs(), now);
        job.complete(worker, Map.of(
                "transcriptId", transcript.getId().toString(),
                "assetId", asset.getId().toString(),
                "segmentCount", rows.size()), now);
        return toSummary(transcript);
    }

    @Transactional
    public MediaTranscriptSummary failWorkerTranscription(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerTranscriptFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireTranscriptionJob(worker, jobId);
        MediaTranscript transcript = requireTranscriptForJob(job);
        MediaAsset asset = transcript.getAsset();
        if (!transcript.getId().equals(request.transcriptId()) || !asset.getId().equals(request.assetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transcript does not match job");
        }
        Instant now = Instant.now(clock);
        String message = safeErrorMessage(request.errorMessage());
        if (Boolean.TRUE.equals(request.terminal())) {
            job.failTerminal(worker, request.errorCode(), message, now);
            transcript.markFailed(request.errorCode(), message, now);
        } else {
            job.fail(worker, request.errorCode(), message, now);
            if (job.getStatus() == JobStatus.FAILED) {
                transcript.markFailed(request.errorCode(), message, now);
            } else {
                transcript.markPendingForRetry(now);
            }
        }
        return toSummary(transcript);
    }

    @Transactional
    public void reconcileRecoveredTranscriptionJob(Job job, Instant now) {
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

    private MediaTranscriptSummary createTranscript(Workspace workspace, MediaAsset asset, String provider, String model) {
        JobSummary jobSummary = jobService.createForWorkspace(
                workspace,
                new JobCreateRequest(JobType.TRANSCRIBE_MEDIA, Map.of(
                        "assetId", asset.getId().toString(),
                        "provider", provider,
                        "model", model)));
        Job job = jobService.getJobEntityForWorkspace(workspace, jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Transcription job was not created"));
        MediaTranscript transcript = transcripts.save(new MediaTranscript(
                workspace,
                asset,
                job,
                provider,
                model,
                Instant.now(clock)));
        return toSummary(transcript);
    }

    private Job requireTranscriptionJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.TRANSCRIBE_MEDIA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a media transcription");
        }
        if (job.getStatus() != JobStatus.RUNNING && job.getStatus() != JobStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not active");
        }
        return job;
    }

    private MediaTranscript requireTranscriptForJob(Job job) {
        return transcripts.findByTranscriptionJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transcript not found"));
    }

    private void validateJobReferencesTranscript(Job job, MediaTranscript transcript, MediaAsset asset) {
        Map<String, Object> payload = job.getPayload();
        if (!asset.getId().toString().equals(String.valueOf(payload.get("assetId")))
                || !transcript.getProvider().equals(String.valueOf(payload.get("provider")))
                || !transcript.getModel().equals(String.valueOf(payload.get("model")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job payload does not match transcript");
        }
    }

    private void validateSourceForTranscription(MediaAsset asset) {
        if (asset.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not ready");
        }
        if (asset.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not inspected");
        }
        if (!Boolean.TRUE.equals(asset.getHasAudio())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset has no audio stream");
        }
        if (asset.getDurationMs() == null || asset.getDurationMs() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset duration is not known");
        }
        if (asset.getDurationMs() > transcriptProperties.getMaxMediaDurationMs()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is too long for transcription");
        }
    }

    private List<SegmentValue> validateSegments(MediaAsset asset, List<WorkerTranscriptSegmentRequest> requestSegments) {
        if (requestSegments == null || requestSegments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transcript segments are required");
        }
        if (requestSegments.size() > transcriptProperties.getMaxSegments()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many transcript segments");
        }
        long maxEnd = asset.getDurationMs() + transcriptProperties.getTimestampToleranceMs();
        List<SegmentValue> values = new ArrayList<>();
        int totalText = 0;
        int sequence = 1;
        long previousStart = -1;
        long previousEnd = -1;
        for (WorkerTranscriptSegmentRequest segment : requestSegments.stream()
                .sorted(Comparator.comparingLong(s -> s.startMs() == null ? Long.MIN_VALUE : s.startMs()))
                .toList()) {
            if (segment.startMs() == null || segment.endMs() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segment timestamps are required");
            }
            long startMs = segment.startMs();
            long endMs = segment.endMs();
            if (startMs < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segment startMs must be non-negative");
            }
            if (endMs <= startMs) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segment endMs must be after startMs");
            }
            if (endMs > maxEnd) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segment exceeds asset duration");
            }
            if (previousStart > startMs) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segments are not ordered");
            }
            long overlap = previousEnd > startMs ? previousEnd - startMs : 0;
            if (overlap > transcriptProperties.getTimestampToleranceMs()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segment overlap is too large");
            }
            String text = normalizeWhitespace(segment.text());
            if (text.isBlank() || text.length() > transcriptProperties.getMaxSegmentTextLength()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segment text is invalid");
            }
            totalText += text.length();
            if (totalText > transcriptProperties.getMaxTotalTextLength()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transcript text is too large");
            }
            BigDecimal confidence = segment.confidence();
            if (confidence != null && (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Segment confidence is outside the allowed range");
            }
            values.add(new SegmentValue(sequence++, startMs, endMs, text, confidence));
            previousStart = startMs;
            previousEnd = endMs;
        }
        return values;
    }

    private MediaTranscriptSummary toSummary(MediaTranscript transcript) {
        return new MediaTranscriptSummary(
                transcript.getId(),
                transcript.getAsset().getId(),
                transcript.getStatus(),
                transcript.getTranscriptionJob().getId(),
                transcript.getProvider(),
                transcript.getModel(),
                transcript.getDetectedLanguage(),
                transcript.getDurationMs(),
                transcript.getErrorCode(),
                transcript.getErrorMessage(),
                transcript.getCreatedAt(),
                transcript.getStartedAt(),
                transcript.getCompletedAt(),
                transcript.getUpdatedAt(),
                segments.findByTranscriptOrderBySequenceAsc(transcript).stream()
                        .map(this::toSummary)
                        .toList());
    }

    private TranscriptSegmentSummary toSummary(TranscriptSegment segment) {
        return new TranscriptSegmentSummary(
                segment.getId(),
                segment.getSequence(),
                segment.getStartMs(),
                segment.getEndMs(),
                segment.getText(),
                segment.getConfidence());
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String safeShort(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private String safeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Transcription failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private record SegmentValue(int sequence, long startMs, long endMs, String text, BigDecimal confidence) {
    }
}
