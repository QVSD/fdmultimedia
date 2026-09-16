package com.fdmultimedia.api.transcripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
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
import java.time.Duration;
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

class TranscriptServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final MediaTranscriptRepository transcripts = mock(MediaTranscriptRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final MediaProperties mediaProperties = new MediaProperties();
    private final TranscriptProperties transcriptProperties = new TranscriptProperties();
    private final TranscriptService service = new TranscriptService(
            authService,
            assets,
            transcripts,
            segments,
            jobService,
            storage,
            mediaProperties,
            transcriptProperties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private Worker worker;
    private WorkerPrincipal workerPrincipal;

    @BeforeEach
    void setUp() {
        mediaProperties.setMaxDownloadSizeBytes(1_000_000);
        mediaProperties.setConnectTimeout(Duration.ofSeconds(5));
        mediaProperties.setReadTimeout(Duration.ofSeconds(20));
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        WorkerCredential credential = new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash");
        worker = new Worker(workspace, credential, registration("machine-1"), NOW.minusSeconds(5));
        workerPrincipal = new WorkerPrincipal(credential);
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        doAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            Worker assignedWorker = invocation.getArgument(1);
            Map<String, Object> result = invocation.getArgument(2);
            Instant now = invocation.getArgument(3);
            job.complete(assignedWorker, result, now);
            return null;
        }).when(jobService).completeOwnedJob(any(Job.class), any(Worker.class), any(Map.class), any(Instant.class));
        doAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            Worker assignedWorker = invocation.getArgument(1);
            String errorCode = invocation.getArgument(2);
            String errorMessage = invocation.getArgument(3);
            boolean terminal = invocation.getArgument(4);
            Instant now = invocation.getArgument(5);
            if (terminal) {
                job.failTerminal(assignedWorker, errorCode, errorMessage, now);
            } else {
                job.fail(assignedWorker, errorCode, errorMessage, now);
            }
            return null;
        }).when(jobService).failOwnedJob(any(Job.class), any(Worker.class), any(), any(), anyBoolean(), any(Instant.class));
    }

    @Test
    void createsTranscriptAndTranscriptionJobForInspectedAudioAsset() {
        MediaAsset asset = inspectedAsset(true, true);
        Job job = transcriptionJob(asset.getId());
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(transcripts.findFirstByWorkspaceAndAssetAndProviderAndModelAndStatusInOrderByCreatedAtDesc(
                workspace, asset, "LOCAL_WHISPER_CLI", "local", List.of(TranscriptStatus.PENDING, TranscriptStatus.RUNNING)))
                .thenReturn(Optional.empty());
        when(jobService.createForWorkspace(any(), any(JobCreateRequest.class))).thenReturn(jobSummary(job));
        when(jobService.getJobEntityForWorkspace(workspace, job.getId())).thenReturn(Optional.of(job));
        when(transcripts.save(any(MediaTranscript.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(segments.findByTranscriptOrderBySequenceAsc(any())).thenReturn(List.of());

        MediaTranscriptSummary summary = service.createTranscript(user, asset.getId());

        ArgumentCaptor<JobCreateRequest> request = ArgumentCaptor.forClass(JobCreateRequest.class);
        verify(jobService).createForWorkspace(any(), request.capture());
        assertThat(request.getValue().type()).isEqualTo(JobType.TRANSCRIBE_MEDIA);
        assertThat(summary.status()).isEqualTo(TranscriptStatus.PENDING);
        assertThat(summary.assetId()).isEqualTo(asset.getId());
    }

    @Test
    void rejectsVideoOnlyAsset() {
        MediaAsset asset = inspectedAsset(true, false);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.createTranscript(user, asset.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void completesTranscriptAtomicallyWithValidatedOrderedSegments() {
        MediaAsset asset = inspectedAsset(true, true);
        Job job = transcriptionJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        MediaTranscript transcript = new MediaTranscript(workspace, asset, job, "LOCAL_WHISPER_CLI", "local", NOW.minusSeconds(5));
        transcript.markRunning(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(transcripts.findByTranscriptionJobId(job.getId())).thenReturn(Optional.of(transcript));
        when(segments.findByTranscriptOrderBySequenceAsc(transcript)).thenReturn(List.of());

        MediaTranscriptSummary summary = service.completeWorkerTranscription(
                workerPrincipal,
                job.getId(),
                new WorkerTranscriptCompletionRequest(
                        "machine-1",
                        transcript.getId(),
                        asset.getId(),
                        "en",
                        12_000L,
                        List.of(new WorkerTranscriptSegmentRequest(0L, 2_000L, " Hello   world ", null))));

        assertThat(summary.status()).isEqualTo(TranscriptStatus.SUCCEEDED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        ArgumentCaptor<List<TranscriptSegment>> saved = ArgumentCaptor.forClass(List.class);
        verify(segments).saveAll(saved.capture());
        assertThat(saved.getValue().getFirst().getText()).isEqualTo("Hello world");
    }

    @Test
    void rejectsSegmentBeyondAssetDurationWithoutSucceedingJob() {
        MediaAsset asset = inspectedAsset(true, true);
        Job job = transcriptionJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        MediaTranscript transcript = new MediaTranscript(workspace, asset, job, "LOCAL_WHISPER_CLI", "local", NOW.minusSeconds(5));
        transcript.markRunning(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(transcripts.findByTranscriptionJobId(job.getId())).thenReturn(Optional.of(transcript));

        assertThatThrownBy(() -> service.completeWorkerTranscription(
                workerPrincipal,
                job.getId(),
                new WorkerTranscriptCompletionRequest(
                        "machine-1",
                        transcript.getId(),
                        asset.getId(),
                        "en",
                        12_000L,
                        List.of(new WorkerTranscriptSegmentRequest(0L, 20_000L, "too long", null)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(transcript.getStatus()).isEqualTo(TranscriptStatus.RUNNING);
    }

    @Test
    void authorizesWorkerWithPresignedSourceDownload() {
        MediaAsset asset = inspectedAsset(true, true);
        Job job = transcriptionJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        MediaTranscript transcript = new MediaTranscript(workspace, asset, job, "LOCAL_WHISPER_CLI", "local", NOW.minusSeconds(5));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(transcripts.findByTranscriptionJobId(job.getId())).thenReturn(Optional.of(transcript));
        when(storage.presignedGet("storage-key")).thenReturn(new StorageAccess("http://minio/source", "media-assets", "storage-key", NOW.plusSeconds(60)));

        WorkerTranscriptAuthorizationResponse response = service.authorizeWorkerTranscription(workerPrincipal, job.getId(), "machine-1");

        assertThat(response.sourceDownloadUrl()).isEqualTo("http://minio/source");
        assertThat(response.transcriptId()).isEqualTo(transcript.getId());
        assertThat(transcript.getStatus()).isEqualTo(TranscriptStatus.RUNNING);
    }

    private MediaAsset inspectedAsset(boolean hasVideo, boolean hasAudio) {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        asset.markImporting(NOW.minusSeconds(5));
        asset.markReady(new MediaImportMetadata("media.mp4", "video/mp4", 12_000, "0".repeat(64), null, null, null, null, null, "mp4"), "media-assets", "storage-key", NOW.minusSeconds(4));
        Job inspectionJob = new Job(workspace, JobType.INSPECT_MEDIA, Map.of("assetId", asset.getId().toString()), 3, NOW.minusSeconds(3));
        inspectionJob.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        inspectionJob.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachInspectionJob(inspectionJob, NOW.minusSeconds(2));
        asset.markInspecting(NOW.minusSeconds(1));
        asset.markInspected(new MediaInspectionMetadata(
                12_000L,
                hasVideo ? 1920 : null,
                hasVideo ? 1080 : null,
                hasVideo ? "h264" : null,
                hasAudio ? "aac" : null,
                "mp4",
                hasVideo ? new BigDecimal("29.970") : null,
                800_000L,
                hasVideo,
                hasAudio), NOW);
        return asset;
    }

    private Job transcriptionJob(UUID assetId) {
        return new Job(workspace, JobType.TRANSCRIBE_MEDIA, Map.of(
                "assetId", assetId.toString(),
                "provider", "LOCAL_WHISPER_CLI",
                "model", "local"), 3, NOW);
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

    private WorkerRegistrationRequest registration(String machineIdentifier) {
        return new WorkerRegistrationRequest(
                machineIdentifier,
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
}
