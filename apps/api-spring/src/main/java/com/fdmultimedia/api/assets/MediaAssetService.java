package com.fdmultimedia.api.assets;

import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobCreateRequest;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.jobs.JobStatus;
import com.fdmultimedia.api.jobs.JobSummary;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.jobs.WorkerJobUpdateRequest;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workers.Worker;
import com.fdmultimedia.api.workers.security.WorkerPrincipal;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaAssetService {

    private static final Pattern SHA_256 = Pattern.compile("^[a-fA-F0-9]{64}$");

    private final AuthService authService;
    private final MediaAssetRepository assets;
    private final JobService jobService;
    private final ObjectStorageService storage;
    private final UrlSecurityValidator urlSecurityValidator;
    private final MediaProperties mediaProperties;
    private final Clock clock;

    public MediaAssetService(
            AuthService authService,
            MediaAssetRepository assets,
            JobService jobService,
            ObjectStorageService storage,
            UrlSecurityValidator urlSecurityValidator,
            MediaProperties mediaProperties,
            Clock clock) {
        this.authService = authService;
        this.assets = assets;
        this.jobService = jobService;
        this.storage = storage;
        this.urlSecurityValidator = urlSecurityValidator;
        this.mediaProperties = mediaProperties;
        this.clock = clock;
    }

    @Transactional
    public MediaImportResponse createImport(AuthenticatedUser principal, MediaImportRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        AppUser user = membership.getUser();
        String sourceUrl = urlSecurityValidator.validateHttpUrl(request.url()).toString();
        Instant now = Instant.now(clock);
        MediaAsset asset = assets.save(new MediaAsset(workspace, user, sourceUrl, now));
        JobSummary jobSummary = jobService.createForWorkspace(
                workspace,
                new JobCreateRequest(JobType.IMPORT_MEDIA, Map.of("assetId", asset.getId().toString())));
        Job job = jobService.getJobEntityForWorkspace(workspace, jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Import job was not created"));
        asset.attachImportJob(job, now);
        return new MediaImportResponse(toSummary(asset), jobSummary);
    }

    @Transactional(readOnly = true)
    public List<MediaAssetSummary> listFor(AuthenticatedUser principal) {
        Workspace workspace = currentWorkspace(principal);
        return assets.findByWorkspaceOrderByCreatedAtDesc(workspace).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public MediaAssetSummary getFor(AuthenticatedUser principal, UUID assetId) {
        Workspace workspace = currentWorkspace(principal);
        return assets.findByWorkspaceAndId(workspace, assetId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse downloadUrl(AuthenticatedUser principal, UUID assetId) {
        Workspace workspace = currentWorkspace(principal);
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        if (asset.getStatus() != MediaAssetStatus.READY || asset.getStorageKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not ready");
        }
        StorageAccess access = storage.presignedGet(asset.getStorageKey());
        return new DownloadUrlResponse(access.url(), access.expiresAt());
    }

    @Transactional
    public WorkerImportAuthorizationResponse authorizeWorkerImport(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerImportAuthorizationRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireImportJob(worker, jobId);
        MediaAsset asset = requireAssetForJob(job);
        validateJobReferencesAsset(job, asset);
        Instant now = Instant.now(clock);
        asset.markImporting(now);
        String key = storage.objectKey(asset);
        StorageAccess access = storage.presignedPut(key);
        return new WorkerImportAuthorizationResponse(
                asset.getId(),
                asset.getSourceUrl(),
                access.url(),
                access.bucket(),
                access.key(),
                mediaProperties.getMaxDownloadSizeBytes(),
                (int) mediaProperties.getConnectTimeout().toSeconds(),
                (int) mediaProperties.getReadTimeout().toSeconds(),
                mediaProperties.getMaxRedirects());
    }

    @Transactional
    public MediaAssetSummary completeWorkerImport(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerImportCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireImportJob(worker, jobId);
        MediaAsset asset = requireAssetForJob(job);
        if (!asset.getId().equals(request.assetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset does not match import job");
        }
        String expectedKey = storage.objectKey(asset);
        if (!expectedKey.equals(request.storageKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Storage key does not match asset");
        }
        if (!storage.bucket().equals(request.storageBucket())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Storage bucket does not match asset");
        }
        validateCompletionMetadata(request);
        Instant now = Instant.now(clock);
        asset.markReady(request.metadata(), request.storageBucket(), request.storageKey(), now);
        job.complete(worker, Map.of(
                "assetId", asset.getId().toString(),
                "checksumSha256", request.checksumSha256(),
                "fileSizeBytes", request.fileSizeBytes()), now);
        return toSummary(asset);
    }

    @Transactional
    public MediaAssetSummary failWorkerImport(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerImportFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireImportJob(worker, jobId);
        MediaAsset asset = requireAssetForJob(job);
        if (!asset.getId().equals(request.assetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset does not match import job");
        }
        Instant now = Instant.now(clock);
        if (request.terminal()) {
            job.failTerminal(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            asset.markFailed(request.errorCode(), safeErrorMessage(request.errorMessage()), now);
        } else {
            job.fail(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            if (job.getStatus() == JobStatus.FAILED) {
                asset.markFailed(request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            } else {
                asset.markPendingForRetry(now);
            }
        }
        return toSummary(asset);
    }

    private Job requireImportJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.IMPORT_MEDIA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a media import");
        }
        if (job.getStatus() != JobStatus.RUNNING && job.getStatus() != JobStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not active");
        }
        return job;
    }

    private MediaAsset requireAssetForJob(Job job) {
        return assets.findByImportJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
    }

    private void validateJobReferencesAsset(Job job, MediaAsset asset) {
        Object payloadAssetId = job.getPayload().get("assetId");
        if (!asset.getId().toString().equals(String.valueOf(payloadAssetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job payload does not match asset");
        }
    }

    private void validateCompletionMetadata(WorkerImportCompletionRequest request) {
        if (request.fileSizeBytes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Imported media is empty");
        }
        if (!SHA_256.matcher(request.checksumSha256()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media checksum");
        }
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private String safeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Import failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private MediaAssetSummary toSummary(MediaAsset asset) {
        Job importJob = asset.getImportJob();
        return new MediaAssetSummary(
                asset.getId(),
                asset.getSourceType(),
                asset.getSourceUrl(),
                asset.getStatus(),
                asset.getOriginalFilename(),
                asset.getContentType(),
                asset.getFileSizeBytes(),
                asset.getChecksumSha256(),
                asset.getDurationMs(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getVideoCodec(),
                asset.getAudioCodec(),
                asset.getContainerFormat(),
                importJob == null ? null : importJob.getId(),
                asset.getErrorCode(),
                asset.getErrorMessage(),
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                asset.getReadyAt());
    }
}
