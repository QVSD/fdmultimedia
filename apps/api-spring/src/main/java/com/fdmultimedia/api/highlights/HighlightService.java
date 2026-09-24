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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        String requestedAnalyzerType = analyzerType;
        String fallbackReason = null;
        MediaTranscript provenanceTranscript = null;
        String analyzerVersion;
        String configFingerprint = null;
        Map<String, Object> configSnapshot = null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assetId", asset.getId().toString());
        payload.put("analyzerType", analyzerType);
        if (isDeterministicV3Analyzer(analyzerType)) {
            validateSourceForV2Analysis(asset);
            Optional<MediaTranscript> latest = latestSucceededTranscript(workspace, asset);
            if (latest.isEmpty()) {
                analyzerType = properties.getDeterministicAnalyzerType();
                analyzerVersion = properties.getDeterministicAnalyzerVersion();
                fallbackReason = "TRANSCRIPT_MISSING";
            } else {
                MediaTranscript transcript = latest.get();
                String reason = v3FallbackReason(asset, transcript);
                provenanceTranscript = transcript;
                if (reason == null) {
                    payload.put("transcriptId", transcript.getId().toString());
                    analyzerVersion = properties.getV3AnalyzerVersion();
                    configSnapshot = v2ConfigSnapshot();
                } else {
                    analyzerType = properties.getV2AnalyzerType();
                    analyzerVersion = properties.getV2AnalyzerVersion();
                    fallbackReason = reason;
                    payload.put("transcriptId", transcript.getId().toString());
                    configSnapshot = v2ConfigSnapshot();
                }
            }
            payload.put("analyzerType", analyzerType);
            configFingerprint = fingerprint(List.of(requestedAnalyzerType, analyzerType, analyzerVersion,
                    asset.getId(), provenanceTranscript == null ? "none" : provenanceTranscript.getId(),
                    configSnapshot == null ? "none" : configSnapshot.toString(), fallbackReason == null ? "none" : fallbackReason));
        } else if (isSemanticAnalyzer(analyzerType)) {
            validateSourceForSemanticAnalysis(asset);
            MediaTranscript transcript = requireSucceededTranscript(workspace, asset);
            validateTranscriptContext(asset, transcript);
            payload.put("transcriptId", transcript.getId().toString());
            analyzerVersion = properties.getSemanticAnalyzerVersion();
        } else if (isDeterministicV2Analyzer(analyzerType)) {
            validateSourceForV2Analysis(asset);
            MediaTranscript transcript = requireSucceededTranscript(workspace, asset);
            validateTranscriptContextForV2(asset, transcript);
            payload.put("transcriptId", transcript.getId().toString());
            analyzerVersion = properties.getV2AnalyzerVersion();
            configSnapshot = v2ConfigSnapshot();
            configFingerprint = fingerprint(List.of(
                    analyzerType, analyzerVersion, asset.getId(), transcript.getId(), configSnapshot.toString()));
            String finalFingerprint = configFingerprint;
            String finalAnalyzerType = analyzerType;
            Optional<HighlightAnalysis> reusable = analyses.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, asset).stream()
                    .filter(a -> finalAnalyzerType.equals(a.getAnalyzerType()))
                    .filter(a -> finalFingerprint.equals(a.getConfigFingerprint()))
                    .filter(a -> a.getStatus() != HighlightAnalysisStatus.FAILED)
                    .findFirst();
            if (reusable.isPresent()) {
                return toSummary(reusable.get());
            }
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
                configFingerprint,
                configSnapshot,
                now));
        analysis.setExecutionProvenance(requestedAnalyzerType, analyzerType, fallbackReason, provenanceTranscript);
        return toSummary(analysis);
    }

    /**
     * Frozen SEMANTIC_HIGHLIGHTS_V2 configuration, snapshotted at analysis
     * creation time so a later property change never changes how an already
     * recorded analysis is explained, and so the same values are both
     * fingerprinted here and shipped to the Worker via authorizeWorkerAnalysis.
     */
    private Map<String, Object> v2ConfigSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("analyzerVersion", properties.getV2AnalyzerVersion());
        snapshot.put("minDurationMs", properties.getV2MinDurationMs());
        snapshot.put("preferredMinDurationMs", properties.getV2PreferredMinDurationMs());
        snapshot.put("preferredMaxDurationMs", properties.getV2PreferredMaxDurationMs());
        snapshot.put("maxDurationMs", properties.getV2MaxDurationMs());
        snapshot.put("maxSegmentsConsidered", properties.getV2MaxSegmentsConsidered());
        snapshot.put("maxCandidateStarts", properties.getV2MaxCandidateStarts());
        snapshot.put("maxCandidateWindows", properties.getV2MaxCandidateWindows());
        snapshot.put("maxCandidates", properties.getMaxCandidates());
        snapshot.put("overlapSuppressionThreshold", properties.getV2OverlapSuppressionThreshold());
        snapshot.put("similarityThreshold", properties.getV2SimilarityThreshold());
        snapshot.put("minTranscriptCoverage", properties.getV2MinTranscriptCoverage());
        snapshot.put("weightHook", properties.getV2WeightHook());
        snapshot.put("weightCompleteness", properties.getV2WeightCompleteness());
        snapshot.put("weightInformationDensity", properties.getV2WeightInformationDensity());
        snapshot.put("weightSpeechDensity", properties.getV2WeightSpeechDensity());
        snapshot.put("weightBoundary", properties.getV2WeightBoundary());
        snapshot.put("weightCoverage", properties.getV2WeightCoverage());
        snapshot.put("weightRepetitionPenalty", properties.getV2WeightRepetitionPenalty());
        return snapshot;
    }

    private static String fingerprint(List<?> values) {
        try {
            String canonical = values.stream().map(v -> v == null ? "<null>" : v.toString()).reduce((a, b) -> a + "\n" + b).orElse("");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        boolean v2 = isDeterministicV2Analyzer(analysis.getAnalyzerType());
        boolean v3 = isDeterministicV3Analyzer(analysis.getAnalyzerType());
        if (isSemanticAnalyzer(analysis.getAnalyzerType()) || v2 || v3) {
            if (v2 || v3) {
                validateSourceForV2Analysis(asset);
            } else {
                validateSourceForSemanticAnalysis(asset);
            }
            MediaTranscript transcript = requireTranscriptFromJob(job, asset);
            if (v2) {
                validateTranscriptContextForV2(asset, transcript);
            } else {
                validateTranscriptContext(asset, transcript);
            }
            transcriptId = transcript.getId();
            transcriptContext = transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript).stream()
                    .map(segment -> new WorkerHighlightTranscriptSegmentResponse(
                            segment.getId(),
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
                transcriptContext,
                (v2 || v3) ? v2Config() : null);
    }

    private WorkerHighlightV2ConfigResponse v2Config() {
        return new WorkerHighlightV2ConfigResponse(
                properties.getV2MinDurationMs(),
                properties.getV2PreferredMinDurationMs(),
                properties.getV2PreferredMaxDurationMs(),
                properties.getV2MaxDurationMs(),
                properties.getV2MaxSegmentsConsidered(),
                properties.getV2MaxCandidateStarts(),
                properties.getV2MaxCandidateWindows(),
                properties.getV2OverlapSuppressionThreshold(),
                properties.getV2SimilarityThreshold(),
                properties.getV2MinTranscriptCoverage(),
                properties.getV2WeightHook(),
                properties.getV2WeightCompleteness(),
                properties.getV2WeightInformationDensity(),
                properties.getV2WeightSpeechDensity(),
                properties.getV2WeightBoundary(),
                properties.getV2WeightCoverage(),
                properties.getV2WeightRepetitionPenalty());
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
        boolean v2 = isDeterministicV2Analyzer(analysis.getAnalyzerType());
        boolean v3 = isDeterministicV3Analyzer(analysis.getAnalyzerType());
        if (isSemanticAnalyzer(analysis.getAnalyzerType()) || v2 || v3) {
            if (v2 || v3) {
                validateSourceForV2Analysis(asset);
            } else {
                validateSourceForSemanticAnalysis(asset);
            }
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
                        candidate.evidence(),
                        now))
                .toList();
        candidates.saveAll(rows);
        BigDecimal transcriptCoverage = (v2 || v3) ? clampUnit(request.transcriptCoverage()) : null;
        analysis.markSucceeded(transcriptCoverage, now);
        jobService.completeOwnedJob(job, worker, Map.of(
                "analysisId", analysis.getId().toString(),
                "assetId", asset.getId().toString(),
                "candidateCount", rows.size()), now);
        return toSummary(analysis);
    }

    private BigDecimal clampUnit(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
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

    /**
     * Same eligibility bar as {@link #validateSourceForSemanticAnalysis} minus
     * the LLM-prompt-driven {@code semanticMaxAssetDurationMs} cap: V2 has no
     * LLM in its path, so long-form (multi-hour) media must remain usable —
     * scale is instead bounded deterministically by the Worker's candidate
     * generation (segment/window caps), never by rejecting the source.
     */
    private void validateSourceForV2Analysis(MediaAsset asset) {
        if (asset.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not ready");
        }
        if (asset.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not inspected");
        }
        if (!Boolean.TRUE.equals(asset.getHasAudio())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_REQUIRED");
        }
        if (asset.getDurationMs() == null || asset.getDurationMs() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset duration is not known");
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
        boolean transcriptDriven = isSemanticAnalyzer(analysis.getAnalyzerType())
                || isDeterministicV2Analyzer(analysis.getAnalyzerType()) || isDeterministicV3Analyzer(analysis.getAnalyzerType());
        List<TranscriptSegment> groundingSegments = transcriptDriven
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
            ranked.add(new CandidateValue(value.startMs(), value.endMs(), value.score(), value.reason(), rank++, value.evidence()));
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
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate is not grounded in transcript");
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
        validateV3Evidence(candidate, groundingSegments);
        HighlightCandidate.HighlightCandidateEvidence evidence = new HighlightCandidate.HighlightCandidateEvidence(
                clampUnit(candidate.hookScore()),
                clampUnit(candidate.completenessScore()),
                clampUnit(candidate.informationDensityScore()),
                clampUnit(candidate.speechDensityScore()),
                clampUnit(candidate.boundaryScore()),
                clampUnit(candidate.coverageScore()),
                clampUnit(candidate.sceneScore()),
                clampUnit(candidate.audioBoundaryScore()),
                clampUnit(candidate.repetitionPenalty()),
                boundedExplanationLabels(candidate.explanationLabels()),
                boundedExcerpt(candidate.transcriptExcerpt()),
                clampUnit(candidate.baseScore()), clampUnit(candidate.lexicalScore()), clampUnit(candidate.emphasisScore()),
                clampUnit(candidate.selfContainedScore()), clampUnit(candidate.semanticScore()),
                candidate.wordCount() == null ? null : Math.max(0, candidate.wordCount()),
                candidate.firstTranscriptSegmentId(), candidate.lastTranscriptSegmentId(),
                candidate.boundaryStartAdjustmentMs(), candidate.boundaryEndAdjustmentMs());
        return new CandidateValue(startMs, endMs, score.setScale(4, RoundingMode.HALF_UP), reason, 0, evidence);
    }

    private void validateV3Evidence(WorkerHighlightCandidateRequest candidate, List<TranscriptSegment> groundingSegments) {
        if (candidate.wordCount() != null && (candidate.wordCount() < 0 || candidate.wordCount() > 10_000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate word count is invalid");
        }
        if (isBoundaryAdjustmentInvalid(candidate.boundaryStartAdjustmentMs())
                || isBoundaryAdjustmentInvalid(candidate.boundaryEndAdjustmentMs())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate boundary adjustment is invalid");
        }
        java.util.Set<UUID> segmentIds = groundingSegments.stream().map(TranscriptSegment::getId).collect(java.util.stream.Collectors.toSet());
        if (candidate.firstTranscriptSegmentId() != null && !segmentIds.contains(candidate.firstTranscriptSegmentId())
                || candidate.lastTranscriptSegmentId() != null && !segmentIds.contains(candidate.lastTranscriptSegmentId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate transcript provenance is invalid");
        }
    }

    private boolean isBoundaryAdjustmentInvalid(Long adjustmentMs) {
        return adjustmentMs != null && (adjustmentMs < -3_000 || adjustmentMs > 3_000);
    }

    private static final int MAX_EXPLANATION_LABELS = 12;
    private static final int MAX_EXCERPT_LENGTH = 280;

    private List<String> boundedExplanationLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        return labels.stream()
                .filter(label -> label != null && !label.isBlank())
                .map(String::trim)
                .limit(MAX_EXPLANATION_LABELS)
                .toList();
    }

    private String boundedExcerpt(String excerpt) {
        if (excerpt == null || excerpt.isBlank()) {
            return null;
        }
        String trimmed = excerpt.trim();
        return trimmed.length() > MAX_EXCERPT_LENGTH ? trimmed.substring(0, MAX_EXCERPT_LENGTH) : trimmed;
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

    private boolean isDeterministicV2Analyzer(String analyzerType) {
        return properties.getV2AnalyzerType().equals(analyzerType);
    }

    private boolean isDeterministicV3Analyzer(String analyzerType) {
        return properties.getV3AnalyzerType().equals(analyzerType);
    }

    private Optional<MediaTranscript> latestSucceededTranscript(Workspace workspace, MediaAsset asset) {
        return transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, asset).stream()
                .filter(transcript -> transcript.getStatus() == TranscriptStatus.SUCCEEDED)
                .findFirst();
    }

    private String v3FallbackReason(MediaAsset asset, MediaTranscript transcript) {
        String language = transcript.getDetectedLanguage();
        if (language != null && !language.isBlank() && !language.toLowerCase(java.util.Locale.ROOT).startsWith("en")) {
            return "TRANSCRIPT_LANGUAGE_UNSUPPORTED";
        }
        List<TranscriptSegment> segments = transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript);
        if (segments.isEmpty() || segments.stream().allMatch(s -> s.getText() == null || s.getText().isBlank())) {
            return "TRANSCRIPT_EMPTY";
        }
        if (segments.stream().anyMatch(s -> s.getStartMs() < 0 || s.getEndMs() <= s.getStartMs()
                || s.getStartMs() >= asset.getDurationMs() || s.getEndMs() > asset.getDurationMs() + 1000)) {
            return "TRANSCRIPT_INVALID_TIMESTAMPS";
        }
        long words = segments.stream().flatMap(s -> java.util.Arrays.stream(s.getText().trim().split("\\s+"))).filter(w -> !w.isBlank()).count();
        return words < 6 ? "TRANSCRIPT_INSUFFICIENT_SPEECH" : null;
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
        validateTranscriptContext(asset, transcript, properties.getSemanticMaxTranscriptSegments(), properties.getSemanticMaxTranscriptCharacters());
    }

    private void validateTranscriptContextForV2(MediaAsset asset, MediaTranscript transcript) {
        // No character cap and effectively no segment-count cap here (V2 has no
        // LLM prompt to fit into): long-form media must stay usable. Scale is
        // instead bounded deterministically inside the Worker's own candidate
        // generation (see fdm.highlights.v2-max-segments-considered and friends).
        validateTranscriptContext(asset, transcript, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private void validateTranscriptContext(MediaAsset asset, MediaTranscript transcript, int maxSegments, int maxCharacters) {
        List<TranscriptSegment> segments = transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript);
        if (segments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_REQUIRED");
        }
        if (segments.size() > maxSegments) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_TOO_LARGE");
        }
        int totalCharacters = 0;
        long previousEndMs = -1;
        for (TranscriptSegment segment : segments) {
            totalCharacters = Math.addExact(totalCharacters, segment.getText().length());
            if (totalCharacters > maxCharacters) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TRANSCRIPT_TOO_LARGE");
            }
            if (segment.getStartMs() < 0 || segment.getEndMs() <= segment.getStartMs() || segment.getEndMs() > asset.getDurationMs() + 1000) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Transcript timestamps are invalid");
            }
            if (segment.getText() == null || segment.getText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Transcript timestamps are invalid");
            }
            if (segment.getStartMs() < previousEndMs) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Transcript timestamps are invalid");
            }
            previousEndMs = segment.getEndMs();
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
                analysis.getConfigFingerprint(),
                analysis.getConfigSnapshot(),
                analysis.getTranscriptCoverage(),
                analysis.getRequestedAnalyzerType(),
                analysis.getEffectiveAnalyzerType(),
                analysis.getFallbackReason(),
                analysis.getTranscript() == null ? null : analysis.getTranscript().getId(),
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
                candidate.getCreatedAt(),
                candidate.getHookScore(),
                candidate.getCompletenessScore(),
                candidate.getInformationDensityScore(),
                candidate.getSpeechDensityScore(),
                candidate.getBoundaryScore(),
                candidate.getCoverageScore(),
                candidate.getSceneScore(),
                candidate.getAudioBoundaryScore(),
                candidate.getRepetitionPenalty(),
                candidate.getExplanationLabels(),
                candidate.getTranscriptExcerpt(), candidate.getBaseScore(), candidate.getLexicalScore(),
                candidate.getEmphasisScore(), candidate.getSelfContainedScore(), candidate.getSemanticScore(),
                candidate.getWordCount(), candidate.getFirstTranscriptSegmentId(), candidate.getLastTranscriptSegmentId(),
                candidate.getBoundaryStartAdjustmentMs(), candidate.getBoundaryEndAdjustmentMs());
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

    private record CandidateValue(long startMs, long endMs, BigDecimal score, String reason, int rank,
            HighlightCandidate.HighlightCandidateEvidence evidence) {
    }
}
