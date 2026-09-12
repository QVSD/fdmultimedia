package com.fdmultimedia.api.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MediaAssetServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T08:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final UrlSecurityValidator urlSecurityValidator = mock(UrlSecurityValidator.class);
    private final MediaProperties mediaProperties = new MediaProperties();
    private final MediaAssetService service = new MediaAssetService(
            authService,
            assets,
            jobService,
            storage,
            urlSecurityValidator,
            mediaProperties,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private WorkerCredential credential;
    private Worker worker;
    private WorkerPrincipal workerPrincipal;

    @BeforeEach
    void setUp() {
        mediaProperties.setMaxDownloadSizeBytes(1_000_000);
        mediaProperties.setConnectTimeout(Duration.ofSeconds(5));
        mediaProperties.setReadTimeout(Duration.ofSeconds(20));
        mediaProperties.setMaxRedirects(2);
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user))
                .thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        credential = new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash");
        worker = new Worker(workspace, credential, registration("machine-1"), NOW.minusSeconds(5));
        workerPrincipal = new WorkerPrincipal(credential);
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        when(storage.bucket()).thenReturn("media-assets");
    }

    @Test
    void createsMediaAssetAndImportJobFromCurrentWorkspace() {
        when(urlSecurityValidator.validateHttpUrl("https://example.com/video.mp4"))
                .thenReturn(java.net.URI.create("https://example.com/video.mp4"));
        when(assets.save(any(MediaAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Job job = importJob(UUID.randomUUID());
        when(jobService.createForWorkspace(any(), any(JobCreateRequest.class))).thenAnswer(invocation ->
                jobSummary(job));
        when(jobService.getJobEntityForWorkspace(workspace, job.getId())).thenReturn(Optional.of(job));

        MediaImportResponse response = service.createImport(user, new MediaImportRequest("https://example.com/video.mp4"));

        ArgumentCaptor<JobCreateRequest> jobRequest = ArgumentCaptor.forClass(JobCreateRequest.class);
        verify(jobService).createForWorkspace(any(), jobRequest.capture());
        assertThat(jobRequest.getValue().type()).isEqualTo(JobType.IMPORT_MEDIA);
        assertThat(jobRequest.getValue().payload()).containsKey("assetId");
        assertThat(response.asset().status()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(response.asset().importJobId()).isEqualTo(job.getId());
    }

    @Test
    void authorizesWorkerImportWithPresignedUploadAndMarksAssetImporting() {
        MediaAsset asset = asset();
        Job job = importJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachImportJob(job, NOW.minusSeconds(4));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(assets.findByImportJobId(job.getId())).thenReturn(Optional.of(asset));
        when(storage.objectKey(asset)).thenReturn("workspaces/ws/assets/asset/original");
        when(storage.presignedPut("workspaces/ws/assets/asset/original"))
                .thenReturn(new StorageAccess("http://minio/upload", "media-assets", "workspaces/ws/assets/asset/original", NOW.plusSeconds(60)));

        WorkerImportAuthorizationResponse response = service.authorizeWorkerImport(
                workerPrincipal,
                job.getId(),
                new WorkerImportAuthorizationRequest("machine-1"));

        assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.IMPORTING);
        assertThat(response.uploadUrl()).isEqualTo("http://minio/upload");
        assertThat(response.maxDownloadSizeBytes()).isEqualTo(1_000_000);
    }

    @Test
    void completesImportTransactionallyWithJobAndReadyAsset() {
        MediaAsset asset = asset();
        Job job = importJob(asset.getId());
        Job inspectionJob = inspectionJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachImportJob(job, NOW.minusSeconds(4));
        asset.markImporting(NOW.minusSeconds(2));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(assets.findByImportJobId(job.getId())).thenReturn(Optional.of(asset));
        when(storage.objectKey(asset)).thenReturn("workspaces/ws/assets/asset/original");
        when(storage.bucket()).thenReturn("media-assets");
        when(jobService.createForWorkspace(any(), any(JobCreateRequest.class))).thenReturn(jobSummary(inspectionJob));
        when(jobService.getJobEntityForWorkspace(workspace, inspectionJob.getId())).thenReturn(Optional.of(inspectionJob));

        MediaAssetSummary summary = service.completeWorkerImport(workerPrincipal, job.getId(), completion(asset));

        assertThat(summary.status()).isEqualTo(MediaAssetStatus.READY);
        assertThat(summary.checksumSha256()).isEqualTo("0".repeat(64));
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getResult()).containsEntry("assetId", asset.getId().toString());
        assertThat(summary.inspectionStatus()).isEqualTo(MediaInspectionStatus.PENDING);
        assertThat(summary.inspectionJobId()).isEqualTo(inspectionJob.getId());
    }

    @Test
    void completesInspectionAndPersistsMetadataWithoutChangingAssetReadiness() {
        MediaAsset asset = readyAsset();
        Job job = inspectionJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachInspectionJob(job, NOW.minusSeconds(4));
        asset.markInspecting(NOW.minusSeconds(2));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(assets.findByInspectionJobId(job.getId())).thenReturn(Optional.of(asset));

        MediaAssetSummary summary = service.completeWorkerInspection(
                workerPrincipal,
                job.getId(),
                new WorkerInspectionCompletionRequest(
                        "machine-1",
                        asset.getId(),
                        12_345L,
                        1920,
                        1080,
                        "h264",
                        "aac",
                        "mp4",
                        new BigDecimal("29.970"),
                        800_000L,
                        true,
                        true));

        assertThat(summary.status()).isEqualTo(MediaAssetStatus.READY);
        assertThat(summary.inspectionStatus()).isEqualTo(MediaInspectionStatus.INSPECTED);
        assertThat(summary.videoCodec()).isEqualTo("h264");
        assertThat(summary.audioCodec()).isEqualTo("aac");
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void rejectsInspectionCompletionWithoutMediaStreams() {
        MediaAsset asset = readyAsset();
        Job job = inspectionJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachInspectionJob(job, NOW.minusSeconds(4));
        asset.markInspecting(NOW.minusSeconds(2));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(assets.findByInspectionJobId(job.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.completeWorkerInspection(
                workerPrincipal,
                job.getId(),
                new WorkerInspectionCompletionRequest(
                        "machine-1",
                        asset.getId(),
                        12_345L,
                        null,
                        null,
                        null,
                        null,
                        "mp4",
                        null,
                        800_000L,
                        false,
                        false)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(asset.getInspectionStatus()).isEqualTo(MediaInspectionStatus.INSPECTING);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void duplicateImportCompletionCannotCreateDuplicateInspectionJob() {
        MediaAsset asset = asset();
        Job job = importJob(asset.getId());
        Job inspectionJob = inspectionJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachImportJob(job, NOW.minusSeconds(4));
        asset.markImporting(NOW.minusSeconds(2));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(assets.findByImportJobId(job.getId())).thenReturn(Optional.of(asset));
        when(storage.objectKey(asset)).thenReturn("workspaces/ws/assets/asset/original");
        when(storage.bucket()).thenReturn("media-assets");
        when(jobService.createForWorkspace(any(), any(JobCreateRequest.class))).thenReturn(jobSummary(inspectionJob));
        when(jobService.getJobEntityForWorkspace(workspace, inspectionJob.getId())).thenReturn(Optional.of(inspectionJob));

        MediaAssetSummary summary = service.completeWorkerImport(workerPrincipal, job.getId(), completion(asset));

        assertThat(summary.inspectionJobId()).isEqualTo(inspectionJob.getId());
        assertThat(summary.inspectionStatus()).isEqualTo(MediaInspectionStatus.PENDING);
        assertThatThrownBy(() -> service.completeWorkerImport(workerPrincipal, job.getId(), completion(asset)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
        verify(jobService, times(1)).createForWorkspace(any(), any(JobCreateRequest.class));
    }

    @Test
    void terminalInspectionFailureDoesNotFailReadyAsset() {
        MediaAsset asset = readyAsset();
        Job job = inspectionJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachInspectionJob(job, NOW.minusSeconds(4));
        asset.markInspecting(NOW.minusSeconds(2));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(assets.findByInspectionJobId(job.getId())).thenReturn(Optional.of(asset));

        MediaAssetSummary summary = service.failWorkerInspection(
                workerPrincipal,
                job.getId(),
                new WorkerInspectionFailureRequest("machine-1", asset.getId(), "FFPROBE_UNSUPPORTED", "Unsupported", true));

        assertThat(summary.status()).isEqualTo(MediaAssetStatus.READY);
        assertThat(summary.inspectionStatus()).isEqualTo(MediaInspectionStatus.FAILED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void rejectsCompletionForWrongStorageKeyOrChecksum() {
        MediaAsset asset = asset();
        Job job = importJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachImportJob(job, NOW.minusSeconds(4));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(assets.findByImportJobId(job.getId())).thenReturn(Optional.of(asset));
        when(storage.objectKey(asset)).thenReturn("expected-key");

        assertThatThrownBy(() -> service.completeWorkerImport(workerPrincipal, job.getId(), completion(asset)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void downloadUrlRequiresReadyAssetInCurrentWorkspace() {
        MediaAsset pending = asset();
        when(assets.findByWorkspaceAndId(workspace, pending.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.downloadUrl(user, pending.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);

        MediaAsset ready = asset();
        ready.markImporting(NOW.minusSeconds(1));
        ready.markReady(metadata(), "media-assets", "storage-key", NOW);
        when(assets.findByWorkspaceAndId(workspace, ready.getId())).thenReturn(Optional.of(ready));
        when(storage.presignedGet("storage-key"))
                .thenReturn(new StorageAccess("http://minio/download", "media-assets", "storage-key", NOW.plusSeconds(60)));

        DownloadUrlResponse response = service.downloadUrl(user, ready.getId());

        assertThat(response.url()).isEqualTo("http://minio/download");
    }

    @Test
    void terminalWorkerFailureMarksAssetFailed() {
        MediaAsset asset = asset();
        Job job = importJob(asset.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        asset.attachImportJob(job, NOW.minusSeconds(4));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(assets.findByImportJobId(job.getId())).thenReturn(Optional.of(asset));

        MediaAssetSummary summary = service.failWorkerImport(
                workerPrincipal,
                job.getId(),
                new WorkerImportFailureRequest("machine-1", asset.getId(), "UNSUPPORTED_MEDIA", "Not media", true));

        assertThat(summary.status()).isEqualTo(MediaAssetStatus.FAILED);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    private MediaAsset asset() {
        return new MediaAsset(workspace, owner, "https://example.com/video.mp4", NOW);
    }

    private Job importJob(UUID assetId) {
        return new Job(workspace, JobType.IMPORT_MEDIA, Map.of("assetId", assetId.toString()), 3, NOW);
    }

    private Job inspectionJob(UUID assetId) {
        return new Job(workspace, JobType.INSPECT_MEDIA, Map.of("assetId", assetId.toString()), 3, NOW);
    }

    private MediaAsset readyAsset() {
        MediaAsset asset = asset();
        asset.markImporting(NOW.minusSeconds(1));
        asset.markReady(metadata(), "media-assets", "storage-key", NOW);
        return asset;
    }

    private WorkerImportCompletionRequest completion(MediaAsset asset) {
        return new WorkerImportCompletionRequest(
                "machine-1",
                asset.getId(),
                "media-assets",
                "workspaces/ws/assets/asset/original",
                "video.mp4",
                "video/mp4",
                12_345,
                "0".repeat(64),
                null,
                null,
                null,
                null,
                null,
                "mp4");
    }

    private MediaImportMetadata metadata() {
        return new MediaImportMetadata(
                "video.mp4",
                "video/mp4",
                12_345,
                "0".repeat(64),
                null,
                null,
                null,
                null,
                null,
                "mp4");
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
