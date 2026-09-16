package com.fdmultimedia.api.highlights;

import com.fdmultimedia.api.assets.CreateClipRequest;
import com.fdmultimedia.api.assets.CreateClipResponse;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetService;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.assets.MediaInspectionStatus;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobCreateRequest;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.jobs.JobStatus;
import com.fdmultimedia.api.jobs.JobSummary;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.transcripts.MediaTranscript;
import com.fdmultimedia.api.transcripts.MediaTranscriptRepository;
import com.fdmultimedia.api.transcripts.TranscriptSegment;
import com.fdmultimedia.api.transcripts.TranscriptSegmentRepository;
import com.fdmultimedia.api.transcripts.TranscriptStatus;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HighlightService {

    private final AuthService authService;
    private final MediaAssetRepository assets;
    private final HighlightAnalysisRepository analyses;
    private final HighlightCandidateRepository candidates;
    private final MediaTranscriptRepository transcripts;
    private final TranscriptSegmentRepository transcriptSegments;
    private final JobService jobService;
    private final MediaAssetService mediaAssetService;
    private final HighlightProperties properties;
    private final Clock clock;

    @Autowired
    public HighlightService(
            AuthService authService,
            MediaAssetRepository assets,
            HighlightAnalysisRepository analyses,
            HighlightCandidateRepository candidates,
            MediaTranscriptRepository transcripts,
            TranscriptSegmentRepository transcriptSegments,
            JobService jobService,
            MediaAssetService mediaAssetService,
            HighlightProperties properties,
            Clock clock) {
        this.authService = authService;
        this.assets = assets;
        this.analyses = analyses;
        this.candidates = candidates;
        this.transcripts = transcripts;
        this.transcriptSegments = transcriptSegments;
        this.jobService = jobService;
        this.mediaAssetService = mediaAssetService;
        this.properties = properties;
        this.clock = clock;
    }

    HighlightService(
            AuthService authService,
            MediaAssetRepository assets,
            HighlightAnalysisRepository analyses,
            HighlightCandidateRepository candidates,
            JobService jobService,
            MediaAssetService mediaAssetService,
            HighlightProperties properties,
            Clock clock) {
        this(
                authService,
                assets,
                analyses,
                candidates,
                null,
                null,
                jobService,
                mediaAssetService,
                properties,
                clock);
    }

    @Transactional
    public HighlightAnalysisSummary createAnalysis(AuthenticatedUser principal, UUID assetId) {
        return createAnalysis(principal, assetId, null);
    }

    @Transactional
    public HighlightAnalysisSummary createAnalysis(AuthenticatedUser principal, UUID assetId, CreateHighlightAnalysisRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        String analyzerType = requestedAnalyzer(request);
        String analyzerVersion;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assetId", asset.getId().toString());
        payload.put("analyzerType", analyzerType);
        if (isSemanticAnalyzer(analyzerType)) {
            validateSourceForSemanticAnalysis(asset);
            MediaTranscript transcript = requireSucceededTranscript(workspace, asset);
            validateTranscriptContext(asset, transcript);
            payload.put("transcriptId", transcript.getId().toString());
            analyzerVersion = properties.getSemanticAnalyzerVersion();
        } else if (isDeterministicAnalyzer(analyzerType)) {
            validateSourceForAnalysis(asset);
            analyzerVersion = properties.getDeterministicAnalyzerVersion();
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported highlight analyzer");
        }

        JobSummary jobSummary = jobService.createForWorkspace(
                workspace,
                new JobCreateRequest(JobType.ANALYZE_HIGHLIGHTS, payload));
        Job job = jobService.getJobEntityForWorkspace(workspace, jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Analysis job was not created"));
        Instant now = Instant.now(clock);
        HighlightAnalysis analysis = analyses.save(new HighlightAnalysis(
                workspace,
                asset,
                job,
                analyzerType,
                analyzerVersion,
                now));
        return toSummary(analysis);
    }

    @Transactional(readOnly = true)
    public List<HighlightAnalysisSummary> listForAsset(AuthenticatedUser principal, UUID assetId) {
        Workspace workspace = currentWorkspace(principal);
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        return analyses.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, asset).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public HighlightAnalysisSummary getFor(AuthenticatedUser principal, UUID analysisId) {
        Workspace workspace = currentWorkspace(principal);
        return analyses.findByWorkspaceAndId(workspace, analysisId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Highlight analysis not found"));
    }

    @Transactional
    public CreateClipResponse createClipFromCandidate(AuthenticatedUser principal, UUID candidateId) {
        Workspace workspace = currentWorkspace(principal);
        HighlightCandidate candidate = candidates.findByWorkspaceAndId(workspace, candidateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Highlight candidate not found"));
        HighlightAnalysis analysis = candidate.getAnalysis();
        if (analysis.getStatus() != HighlightAnalysisStatus.SUCCEEDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Highlight analysis is not complete");
        }
        long durationMs = candidate.getEndMs() - candidate.getStartMs();
        return mediaAssetService.createClip(
                principal,
                candidate.getAsset().getId(),
                new CreateClipRequest(candidate.getStartMs(), durationMs));
    }

    @Transactional
    public WorkerHighlightAuthorizationResponse authorizeWorkerAnalysis(
            WorkerPrincipal principal,
            UUID jobId,
            String machineIdentifier) {
        Worker worker = jobService.requireOnlineWorker(principal, machineIdentifier);
        Job job = requireAnalysisJob(worker, jobId);
        HighlightAnalysis analysis = requireAnalysisForJob(job);
        MediaAsset asset = analysis.getAsset();
        validateJobReferencesAsset(job, asset);
        List<WorkerHighlightTranscriptSegmentResponse> transcriptContext = List.of();
        UUID transcriptId = null;
        if (isSemanticAnalyzer(analysis.getAnalyzerType())) {
            validateSourceForSemanticAnalysis(asset);
            MediaTranscript transcript = requireTranscriptFromJob(job, asset);
            validateTranscriptContext(asset, transcript);
            transcriptId = transcript.getId();
            transcriptContext = transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript).stream()
                    .map(segment -> new WorkerHighlightTranscriptSegmentResponse(
                            segment.getSequence(),
                            segment.getStartMs(),
                            segment.getEndMs(),
                            segment.getText()))
                    .toList();
        } else {
            validateSourceForAnalysis(asset);
        }
        Instant now = Instant.now(clock);
        analysis.markRunning(now);
        return new WorkerHighlightAuthorizationResponse(
                analysis.getId(),
                asset.getId(),
                asset.getDurationMs(),
                properties.getMaxCandidates(),
                properties.getMinCandidateDurationMs(),
                properties.getMaxCandidateDurationMs(),
                analysis.getAnalyzerType(),
                analysis.getAnalyzerVersion(),
                transcriptId,
                transcriptContext);
    }

    @Transactional
    public HighlightAnalysisSummary completeWorkerAnalysis(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerHighlightCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireAnalysisJob(worker, jobId);
        HighlightAnalysis analysis = requireAnalysisForJob(job);
        MediaAsset asset = analysis.getAsset();
        if (!analysis.getId().equals(request.analysisId()) || !asset.getId().equals(request.assetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis does not match job");
        }
        validateJobReferencesAsset(job, asset);
        if (isSemanticAnalyzer(analysis.getAnalyzerType())) {
            validateSourceForSemanticAnalysis(asset);
            requireTranscriptFromJob(job, asset);
        } else {
            validateSourceForAnalysis(asset);
        }
        Instant now = Instant.now(clock);
        List<CandidateValue> normalized = validateCandidates(asset, analysis, job, request.candidates());
        candidates.deleteByAnalysis(analysis);
        List<HighlightCandidate> rows = normalized.stream()
                .map(candidate -> new HighlightCandidate(
                        analysis,
                        candidate.startMs(),
                        candidate.endMs(),
                        candidate.score(),
                        candidate.reason(),
                        candidate.rank(),
                        now))
                .toList();
        candidates.saveAll(rows);
        analysis.markSucceeded(now);
        jobService.completeOwnedJob(job, worker, Map.of(
                "analysisId", analysis.getId().toString(),
                "assetId", asset.getId().toString(),
                "candidateCount", rows.size()), now);
        return toSummary(analysis);
    }

    @Transactional
    public HighlightAnalysisSummary failWorkerAnalysis(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerHighlightFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireAnalysisJob(worker, jobId);
        HighlightAnalysis analysis = requireAnalysisForJob(job);
        MediaAsset asset = analysis.getAsset();
        if (!analysis.getId().equals(request.analysisId()) || !asset.getId().equals(request.assetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis does not match job");
        }
        Instant now = Instant.now(clock);
        String message = safeErrorMessage(request.errorMessage());
        if (Boolean.TRUE.equals(request.terminal())) {
            jobService.failOwnedJob(job, worker, request.errorCode(), message, true, now);
            analysis.markFailed(request.errorCode(), message, now);
        } else {
            jobService.failOwnedJob(job, worker, request.errorCode(), message, false, now);
            if (job.getStatus() == JobStatus.FAILED) {
                analysis.markFailed(request.errorCode(), message, now);
            } else {
                analysis.markPendingForRetry(now);
            }
        }
        return toSummary(analysis);
    }

    @Transactional
    public void reconcileRecoveredAnalysisJob(Job job, Instant now) {
        if (job.getType() != JobType.ANALYZE_HIGHLIGHTS) {
            return;
        }
        analyses.findByAnalysisJobId(job.getId()).ifPresent(analysis -> {
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

    private Job requireAnalysisJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.ANALYZE_HIGHLIGHTS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a highlight analysis");
        }
        if (job.getStatus() != JobStatus.RUNNING && job.getStatus() != JobStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not active");
        }
        return job;
    }

    private HighlightAnalysis requireAnalysisForJob(Job job) {
        return analyses.findByAnalysisJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Highlight analysis not found"));
    }

    private void validateSourceForAnalysis(MediaAsset asset) {
        if (asset.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not ready");
        }
        if (asset.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not inspected");
        }
        if (!Boolean.TRUE.equals(asset.getHasVideo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset must contain video");
        }
        if (asset.getDurationMs() == null || asset.getDurationMs() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset duration is not known");
        }
    }

    private void validateSourceForSemanticAnalysis(MediaAsset asset) {
        if (asset.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not ready");
        }
        if (asset.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not inspected");
        }
        if (!Boolean.TRUE.equals(asset.getHasAudio())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset transcript audio is required");
        }
        if (asset.getDurationMs() == null || asset.getDurationMs() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset duration is not known");
        }
        if (asset.getDurationMs() > properties.getSemanticMaxAssetDurationMs()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transcript is too large for semantic analysis");
        }
    }

    private void validateJobReferencesAsset(Job job, MediaAsset asset) {
        Object payloadAssetId = job.getPayload().get("assetId");
        if (!asset.getId().toString().equals(String.valueOf(payloadAssetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job payload does not match asset");
        }
    }

    private List<CandidateValue> validateCandidates(MediaAsset asset, HighlightAnalysis analysis, Job job, List<WorkerHighlightCandidateRequest> requestCandidates) {
        if (requestCandidates == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidates are required");
        }
        if (requestCandidates.size() > properties.getMaxCandidates()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many highlight candidates");
        }
        long sourceDuration = asset.getDurationMs();
        List<TranscriptSegment> groundingSegments = isSemanticAnalyzer(analysis.getAnalyzerType())
                ? transcriptSegments.findByTranscriptOrderBySequenceAsc(requireTranscriptFromJob(job, asset))
                : List.of();
        List<CandidateValue> values = requestCandidates.stream()
                .map(candidate -> validateCandidate(sourceDuration, candidate, groundingSegments))
                .sorted(Comparator
                        .comparing(CandidateValue::score).reversed()
                        .thenComparingLong(CandidateValue::startMs)
                        .thenComparingLong(CandidateValue::endMs)
                        .thenComparing(CandidateValue::reason))
                .toList();
        int rank = 1;
        List<CandidateValue> ranked = new java.util.ArrayList<>();
        for (CandidateValue value : values) {
            ranked.add(new CandidateValue(value.startMs(), value.endMs(), value.score(), value.reason(), rank++));
        }
        return ranked;
    }

    private CandidateValue validateCandidate(long sourceDuration, WorkerHighlightCandidateRequest candidate, List<TranscriptSegment> groundingSegments) {
        long startMs = candidate.startMs();
        long endMs = candidate.endMs();
        if (!groundingSegments.isEmpty()) {
            long[] snapped = snapToTranscriptBoundaries(startMs, endMs, groundingSegments);
            startMs = snapped[0];
            endMs = snapped[1];
            if (!overlapsTranscript(startMs, endMs, groundingSegments)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Semantic candidate is not grounded in transcript");
            }
        }
        if (startMs < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate startMs must be non-negative");
        }
        if (endMs <= startMs) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate endMs must be after startMs");
        }
        if (endMs > sourceDuration) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate exceeds asset duration");
        }
        long durationMs = endMs - startMs;
        if (durationMs < properties.getMinCandidateDurationMs() || durationMs > properties.getMaxCandidateDurationMs()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate duration is outside the allowed range");
        }
        BigDecimal score = candidate.score();
        if (score == null || score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate score is outside the allowed range");
        }
        String reason = candidate.reason() == null ? "" : candidate.reason().trim();
        if (reason.isBlank() || reason.length() > properties.getMaxReasonLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate reason is invalid");
        }
        return new CandidateValue(startMs, endMs, score.setScale(4, RoundingMode.HALF_UP), reason, 0);
    }

    private String requestedAnalyzer(CreateHighlightAnalysisRequest request) {
        if (request == null || request.analyzer() == null || request.analyzer().isBlank()) {
            return properties.getDeterministicAnalyzerType();
        }
        return request.analyzer().trim();
    }

    private boolean isDeterministicAnalyzer(String analyzerType) {
        return properties.getDeterministicAnalyzerType().equals(analyzerType);
    }

    private boolean isSemanticAnalyzer(String analyzerType) {
        return properties.getSemanticAnalyzerType().equals(analyzerType);
    }

    private MediaTranscript requireSucceededTranscript(Workspace workspace, MediaAsset asset) {
        return transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, asset).stream()
                .filter(transcript -> transcript.getStatus() == TranscriptStatus.SUCCEEDED)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_REQUIRED"));
    }

    private MediaTranscript requireTranscriptFromJob(Job job, MediaAsset asset) {
        Object payloadTranscriptId = job.getPayload().get("transcriptId");
        if (payloadTranscriptId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_REQUIRED");
        }
        UUID transcriptId;
        try {
            transcriptId = UUID.fromString(String.valueOf(payloadTranscriptId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job transcript reference is invalid");
        }
        MediaTranscript transcript = transcripts.findByWorkspaceAndId(asset.getWorkspace(), transcriptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_REQUIRED"));
        if (!transcript.getAsset().getId().equals(asset.getId()) || transcript.getStatus() != TranscriptStatus.SUCCEEDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_REQUIRED");
        }
        return transcript;
    }

    private void validateTranscriptContext(MediaAsset asset, MediaTranscript transcript) {
        List<TranscriptSegment> segments = transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript);
        if (segments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_REQUIRED");
        }
        if (segments.size() > properties.getSemanticMaxTranscriptSegments()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_TOO_LARGE");
        }
        int totalCharacters = 0;
        for (TranscriptSegment segment : segments) {
            totalCharacters = Math.addExact(totalCharacters, segment.getText().length());
            if (totalCharacters > properties.getSemanticMaxTranscriptCharacters()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_TOO_LARGE");
            }
            if (segment.getStartMs() < 0 || segment.getEndMs() <= segment.getStartMs() || segment.getEndMs() > asset.getDurationMs() + 1000) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Transcript timestamps are invalid");
            }
        }
    }

    private long[] snapToTranscriptBoundaries(long startMs, long endMs, List<TranscriptSegment> segments) {
        long snappedStart = snapBoundary(startMs, segments, true);
        long snappedEnd = snapBoundary(endMs, segments, false);
        return new long[] {snappedStart, snappedEnd};
    }

    private long snapBoundary(long value, List<TranscriptSegment> segments, boolean start) {
        long best = value;
        long bestDistance = properties.getSemanticBoundarySnapToleranceMs() + 1;
        for (TranscriptSegment segment : segments) {
            long boundary = start ? segment.getStartMs() : segment.getEndMs();
            long distance = Math.abs(value - boundary);
            if (distance < bestDistance) {
                best = boundary;
                bestDistance = distance;
            }
        }
        return bestDistance <= properties.getSemanticBoundarySnapToleranceMs() ? best : value;
    }

    private boolean overlapsTranscript(long startMs, long endMs, List<TranscriptSegment> segments) {
        return segments.stream().anyMatch(segment -> startMs < segment.getEndMs() && endMs > segment.getStartMs());
    }

    private HighlightAnalysisSummary toSummary(HighlightAnalysis analysis) {
        return new HighlightAnalysisSummary(
                analysis.getId(),
                analysis.getAsset().getId(),
                analysis.getStatus(),
                analysis.getAnalysisJob().getId(),
                analysis.getAnalyzerType(),
                analysis.getAnalyzerVersion(),
                analysis.getErrorCode(),
                analysis.getErrorMessage(),
                analysis.getCreatedAt(),
                analysis.getUpdatedAt(),
                analysis.getCompletedAt(),
                candidates.findByAnalysisOrderByRankAsc(analysis).stream()
                        .map(this::toSummary)
                        .toList());
    }

    private HighlightCandidateSummary toSummary(HighlightCandidate candidate) {
        return new HighlightCandidateSummary(
                candidate.getId(),
                candidate.getAnalysis().getId(),
                candidate.getAsset().getId(),
                candidate.getStartMs(),
                candidate.getEndMs(),
                candidate.getEndMs() - candidate.getStartMs(),
                candidate.getScore(),
                candidate.getReason(),
                candidate.getRank(),
                candidate.getCreatedAt());
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private String safeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Highlight analysis failed";
        }
        return message.length() > properties.getMaxReasonLength() ? message.substring(0, properties.getMaxReasonLength()) : message;
    }

    private record CandidateValue(long startMs, long endMs, BigDecimal score, String reason, int rank) {
    }
}
