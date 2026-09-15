package com.fdmultimedia.api.highlights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.assets.CreateClipRequest;
import com.fdmultimedia.api.assets.CreateClipResponse;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetService;
import com.fdmultimedia.api.assets.MediaAssetSummary;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.assets.MediaAssetSourceType;
import com.fdmultimedia.api.assets.MediaDerivationType;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
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
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.WorkerCredential;
import com.fdmultimedia.api.workers.WorkerRegistrationRequest;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.math.BigDecimal;
import java.time.Clock;
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

class HighlightServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final HighlightAnalysisRepository analyses = mock(HighlightAnalysisRepository.class);
    private final HighlightCandidateRepository candidates = mock(HighlightCandidateRepository.class);
    private final MediaTranscriptRepository transcripts = mock(MediaTranscriptRepository.class);
    private final TranscriptSegmentRepository transcriptSegments = mock(TranscriptSegmentRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final MediaAssetService mediaAssetService = mock(MediaAssetService.class);
    private final HighlightProperties properties = new HighlightProperties();
    private final HighlightService service = new HighlightService(
            authService,
            assets,
            analyses,
            candidates,
            transcripts,
            transcriptSegments,
            jobService,
            mediaAssetService,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private MediaAsset asset;
    private Job analysisJob;
    private HighlightAnalysis analysis;
    private Worker worker;
    private WorkerPrincipal workerPrincipal;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fd-multimedia");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user))
                .thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        asset = inspectedVideoAsset();
        analysisJob = new Job(workspace, JobType.ANALYZE_HIGHLIGHTS, Map.of("assetId", asset.getId().toString()), 3, NOW);
        analysis = new HighlightAnalysis(workspace, asset, analysisJob, "DETERMINISTIC_V1", "1", NOW);
        WorkerCredential credential = new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash");
        worker = new Worker(workspace, credential, registration(), NOW);
        workerPrincipal = new WorkerPrincipal(credential);
    }

    @Test
    void createsAnalysisAndAnalyzeJobForInspectedVideo() {
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(analysisJob));
        when(jobService.getJobEntityForWorkspace(workspace, analysisJob.getId())).thenReturn(Optional.of(analysisJob));
        when(analyses.save(any(HighlightAnalysis.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(candidates.findByAnalysisOrderByRankAsc(any())).thenReturn(List.of());

        HighlightAnalysisSummary summary = service.createAnalysis(user, asset.getId());

        ArgumentCaptor<JobCreateRequest> request = ArgumentCaptor.forClass(JobCreateRequest.class);
        verify(jobService).createForWorkspace(any(), request.capture());
        assertThat(request.getValue().type()).isEqualTo(JobType.ANALYZE_HIGHLIGHTS);
        assertThat(request.getValue().payload()).containsEntry("assetId", asset.getId().toString());
        assertThat(summary.status()).isEqualTo(HighlightAnalysisStatus.PENDING);
        assertThat(summary.analyzerType()).isEqualTo("DETERMINISTIC_V1");
    }

    @Test
    void rejectsSourceWithoutVideo() {
        MediaAsset audioOnly = inspectedAsset(false, true);
        when(assets.findByWorkspaceAndId(workspace, audioOnly.getId())).thenReturn(Optional.of(audioOnly));

        assertThatThrownBy(() -> service.createAnalysis(user, audioOnly.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void semanticAnalysisRequiresSucceededTranscript() {
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, asset)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createAnalysis(
                user,
                asset.getId(),
                new CreateHighlightAnalysisRequest("TRANSCRIPT_SEMANTIC_V1")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("reason")
                .isEqualTo("TRANSCRIPT_REQUIRED");
    }

    @Test
    void createsSemanticAnalysisWithTranscriptReference() {
        MediaTranscript transcript = succeededTranscript();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(transcripts.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, asset)).thenReturn(List.of(transcript));
        when(transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript)).thenReturn(segments(transcript));
        when(jobService.createForWorkspace(any(), any())).thenReturn(jobSummary(analysisJob));
        when(jobService.getJobEntityForWorkspace(workspace, analysisJob.getId())).thenReturn(Optional.of(analysisJob));
        when(analyses.save(any(HighlightAnalysis.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(candidates.findByAnalysisOrderByRankAsc(any())).thenReturn(List.of());

        HighlightAnalysisSummary summary = service.createAnalysis(
                user,
                asset.getId(),
                new CreateHighlightAnalysisRequest("TRANSCRIPT_SEMANTIC_V1"));

        ArgumentCaptor<JobCreateRequest> request = ArgumentCaptor.forClass(JobCreateRequest.class);
        verify(jobService).createForWorkspace(any(), request.capture());
        assertThat(request.getValue().payload())
                .containsEntry("analyzerType", "TRANSCRIPT_SEMANTIC_V1")
                .containsEntry("transcriptId", transcript.getId().toString());
        assertThat(summary.analyzerType()).isEqualTo("TRANSCRIPT_SEMANTIC_V1");
    }

    @Test
    void completesAnalysisWithValidatedRankedCandidates() {
        analysis.markRunning(NOW);
        analysisJob.claim(worker, NOW.minusSeconds(1), NOW.plusSeconds(20));
        analysisJob.start(worker, NOW, NOW.plusSeconds(20));
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, analysisJob.getId())).thenReturn(analysisJob);
        when(analyses.findByAnalysisJobId(analysisJob.getId())).thenReturn(Optional.of(analysis));
        when(candidates.findByAnalysisOrderByRankAsc(analysis)).thenAnswer(invocation -> List.of(
                new HighlightCandidate(analysis, 4_000, 9_000, new BigDecimal("0.9000"), "best", 1, NOW),
                new HighlightCandidate(analysis, 1_000, 6_000, new BigDecimal("0.7000"), "good", 2, NOW)));

        HighlightAnalysisSummary summary = service.completeWorkerAnalysis(
                workerPrincipal,
                analysisJob.getId(),
                new WorkerHighlightCompletionRequest(
                        "machine-1",
                        analysis.getId(),
                        asset.getId(),
                        List.of(
                                new WorkerHighlightCandidateRequest(1_000L, 6_000L, new BigDecimal("0.7000"), "good"),
                                new WorkerHighlightCandidateRequest(4_000L, 9_000L, new BigDecimal("0.9000"), "best"))));

        assertThat(summary.status()).isEqualTo(HighlightAnalysisStatus.SUCCEEDED);
        assertThat(analysisJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(summary.candidates()).extracting(HighlightCandidateSummary::rank).containsExactly(1, 2);
        verify(candidates).deleteByAnalysis(analysis);
        verify(candidates).saveAll(any());
    }

    @Test
    void rejectsInvalidCandidateFromWorker() {
        analysis.markRunning(NOW);
        analysisJob.claim(worker, NOW.minusSeconds(1), NOW.plusSeconds(20));
        analysisJob.start(worker, NOW, NOW.plusSeconds(20));
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, analysisJob.getId())).thenReturn(analysisJob);
        when(analyses.findByAnalysisJobId(analysisJob.getId())).thenReturn(Optional.of(analysis));

        assertThatThrownBy(() -> service.completeWorkerAnalysis(
                workerPrincipal,
                analysisJob.getId(),
                new WorkerHighlightCompletionRequest(
                        "machine-1",
                        analysis.getId(),
                        asset.getId(),
                        List.of(new WorkerHighlightCandidateRequest(9_000L, 25_000L, new BigDecimal("0.5"), "bad")))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(analysis.getStatus()).isEqualTo(HighlightAnalysisStatus.RUNNING);
        assertThat(analysisJob.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void authorizesSemanticAnalysisWithTranscriptSegments() {
        MediaTranscript transcript = succeededTranscript();
        Job semanticJob = new Job(
                workspace,
                JobType.ANALYZE_HIGHLIGHTS,
                Map.of(
                        "assetId", asset.getId().toString(),
                        "analyzerType", "TRANSCRIPT_SEMANTIC_V1",
                        "transcriptId", transcript.getId().toString()),
                3,
                NOW);
        HighlightAnalysis semantic = new HighlightAnalysis(workspace, asset, semanticJob, "TRANSCRIPT_SEMANTIC_V1", "1", NOW);
        semanticJob.claim(worker, NOW.minusSeconds(1), NOW.plusSeconds(20));
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, semanticJob.getId())).thenReturn(semanticJob);
        when(analyses.findByAnalysisJobId(semanticJob.getId())).thenReturn(Optional.of(semantic));
        when(transcripts.findByWorkspaceAndId(workspace, transcript.getId())).thenReturn(Optional.of(transcript));
        when(transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript)).thenReturn(segments(transcript));

        WorkerHighlightAuthorizationResponse response = service.authorizeWorkerAnalysis(
                workerPrincipal,
                semanticJob.getId(),
                "machine-1");

        assertThat(response.analyzerType()).isEqualTo("TRANSCRIPT_SEMANTIC_V1");
        assertThat(response.transcriptId()).isEqualTo(transcript.getId());
        assertThat(response.transcriptSegments()).hasSize(2);
        assertThat(semantic.getStatus()).isEqualTo(HighlightAnalysisStatus.RUNNING);
    }

    @Test
    void semanticCompletionRejectsUngroundedCandidate() {
        MediaTranscript transcript = succeededTranscript();
        Job semanticJob = new Job(
                workspace,
                JobType.ANALYZE_HIGHLIGHTS,
                Map.of(
                        "assetId", asset.getId().toString(),
                        "analyzerType", "TRANSCRIPT_SEMANTIC_V1",
                        "transcriptId", transcript.getId().toString()),
                3,
                NOW);
        HighlightAnalysis semantic = new HighlightAnalysis(workspace, asset, semanticJob, "TRANSCRIPT_SEMANTIC_V1", "1", NOW);
        semantic.markRunning(NOW);
        semanticJob.claim(worker, NOW.minusSeconds(1), NOW.plusSeconds(20));
        semanticJob.start(worker, NOW, NOW.plusSeconds(20));
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(jobService.requireJobForWorkerWorkspace(worker, semanticJob.getId())).thenReturn(semanticJob);
        when(analyses.findByAnalysisJobId(semanticJob.getId())).thenReturn(Optional.of(semantic));
        when(transcripts.findByWorkspaceAndId(workspace, transcript.getId())).thenReturn(Optional.of(transcript));
        when(transcriptSegments.findByTranscriptOrderBySequenceAsc(transcript)).thenReturn(segments(transcript));

        assertThatThrownBy(() -> service.completeWorkerAnalysis(
                workerPrincipal,
                semanticJob.getId(),
                new WorkerHighlightCompletionRequest(
                        "machine-1",
                        semantic.getId(),
                        asset.getId(),
                        List.of(new WorkerHighlightCandidateRequest(15_000L, 19_000L, new BigDecimal("0.8"), "not grounded")))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(semantic.getStatus()).isEqualTo(HighlightAnalysisStatus.RUNNING);
        assertThat(semanticJob.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void createsClipFromCandidateThroughExistingMediaService() {
        analysis.markRunning(NOW);
        analysis.markSucceeded(NOW);
        HighlightCandidate candidate = new HighlightCandidate(analysis, 1_000, 6_000, new BigDecimal("0.9000"), "best", 1, NOW);
        when(candidates.findByWorkspaceAndId(workspace, candidate.getId())).thenReturn(Optional.of(candidate));
        when(mediaAssetService.createClip(eq(user), eq(asset.getId()), any(CreateClipRequest.class)))
                .thenReturn(new CreateClipResponse(summary(asset), jobSummary(analysisJob)));

        service.createClipFromCandidate(user, candidate.getId());

        ArgumentCaptor<CreateClipRequest> request = ArgumentCaptor.forClass(CreateClipRequest.class);
        verify(mediaAssetService).createClip(eq(user), eq(asset.getId()), request.capture());
        assertThat(request.getValue().startMs()).isEqualTo(1_000);
        assertThat(request.getValue().durationMs()).isEqualTo(5_000);
    }

    private MediaAsset inspectedVideoAsset() {
        return inspectedAsset(true, true);
    }

    private MediaAsset inspectedAsset(boolean hasVideo, boolean hasAudio) {
        MediaAsset media = new MediaAsset(workspace, owner, "https://example.com/video.mp4", NOW);
        media.markImporting(NOW);
        media.markReady(new MediaImportMetadata(
                "video.mp4",
                "video/mp4",
                1_000_000,
                "0".repeat(64),
                20_000L,
                hasVideo ? 1920 : null,
                hasVideo ? 1080 : null,
                hasVideo ? "h264" : null,
                hasAudio ? "aac" : null,
                "mp4"), "media-assets", "workspaces/ws/assets/a/original", NOW);
        Job inspectionJob = new Job(workspace, JobType.INSPECT_MEDIA, Map.of("assetId", media.getId().toString()), 3, NOW);
        media.attachInspectionJob(inspectionJob, NOW);
        media.markInspecting(NOW);
        media.markInspected(new MediaInspectionMetadata(
                20_000L,
                hasVideo ? 1920 : null,
                hasVideo ? 1080 : null,
                hasVideo ? "h264" : null,
                hasAudio ? "aac" : null,
                "mp4",
                new BigDecimal("29.97"),
                800_000L,
                hasVideo,
                hasAudio), NOW);
        return media;
    }

    private JobSummary jobSummary(Job job) {
        return new JobSummary(
                job.getId(),
                job.getType(),
                job.getStatus(),
                job.getPayload(),
                job.getResult(),
                job.getErrorCode(),
                job.getErrorMessage(),
                null,
                null,
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

    private MediaAssetSummary summary(MediaAsset media) {
        return new MediaAssetSummary(
                media.getId(),
                MediaAssetSourceType.DIRECT_URL,
                media.getSourceUrl(),
                null,
                MediaDerivationType.ORIGINAL,
                MediaAssetStatus.READY,
                media.getOriginalFilename(),
                media.getContentType(),
                media.getFileSizeBytes(),
                media.getChecksumSha256(),
                media.getDurationMs(),
                media.getWidth(),
                media.getHeight(),
                media.getVideoCodec(),
                media.getAudioCodec(),
                media.getContainerFormat(),
                null,
                null,
                MediaInspectionStatus.INSPECTED,
                media.getInspectionJob().getId(),
                null,
                null,
                media.getFrameRate(),
                media.getBitrate(),
                media.getHasVideo(),
                media.getHasAudio(),
                null,
                null,
                media.getCreatedAt(),
                media.getUpdatedAt(),
                media.getReadyAt());
    }

    private WorkerRegistrationRequest registration() {
        return new WorkerRegistrationRequest(
                "machine-1",
                "Node A",
                "Windows 11",
                "amd64",
                "AMD Ryzen",
                16,
                34_359_738_368L,
                null,
                null,
                "fdm-worker/0.1.0");
    }

    private MediaTranscript succeededTranscript() {
        Job transcriptionJob = new Job(
                workspace,
                JobType.TRANSCRIBE_MEDIA,
                Map.of("assetId", asset.getId().toString(), "provider", "WHISPER_CPP", "model", "base"),
                3,
                NOW);
        MediaTranscript transcript = new MediaTranscript(workspace, asset, transcriptionJob, "WHISPER_CPP", "base", NOW);
        transcript.markRunning(NOW);
        transcript.markSucceeded("ro", 20_000L, NOW);
        return transcript;
    }

    private List<TranscriptSegment> segments(MediaTranscript transcript) {
        return List.of(
                new TranscriptSegment(transcript, 0, 0, 5_000, "Bun venit pe platforma multimedia.", null, NOW),
                new TranscriptSegment(transcript, 1, 5_000, 10_000, "Acesta este un test semantic pentru clipuri.", null, NOW));
    }
}
