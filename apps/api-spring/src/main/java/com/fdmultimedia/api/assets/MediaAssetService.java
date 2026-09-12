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
import java.math.BigDecimal;
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
    public CreateClipResponse createClip(AuthenticatedUser principal, UUID sourceAssetId, CreateClipRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        MediaAsset source = assets.findByWorkspaceAndId(workspace, sourceAssetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        if (source.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset is not ready");
        }
        if (source.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset is not inspected");
        }
        validateClipTiming(source, request.startMs(), request.durationMs());

        Instant now = Instant.now(clock);
        MediaAsset output = assets.save(MediaAsset.clipDerivative(workspace, membership.getUser(), source, now));
        JobSummary jobSummary = jobService.createForWorkspace(
                workspace,
                new JobCreateRequest(JobType.CREATE_CLIP, Map.of(
                        "sourceAssetId", source.getId().toString(),
                        "outputAssetId", output.getId().toString(),
                        "startMs", request.startMs(),
                        "durationMs", request.durationMs())));
        Job job = jobService.getJobEntityForWorkspace(workspace, jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Clip job was not created"));
        output.attachProcessingJob(job, now);
        return new CreateClipResponse(toSummary(output), jobSummary);
    }

    @Transactional
    public CreateClipResponse createSocialVertical(AuthenticatedUser principal, UUID sourceAssetId) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        MediaAsset source = assets.findByWorkspaceAndId(workspace, sourceAssetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        validateSocialVerticalSource(source);

        Instant now = Instant.now(clock);
        MediaAsset output = assets.save(MediaAsset.socialVerticalDerivative(workspace, membership.getUser(), source, now));
        JobSummary jobSummary = jobService.createForWorkspace(
                workspace,
                new JobCreateRequest(JobType.CREATE_SOCIAL_VERTICAL, Map.of(
                        "sourceAssetId", source.getId().toString(),
                        "outputAssetId", output.getId().toString())));
        Job job = jobService.getJobEntityForWorkspace(workspace, jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Social vertical job was not created"));
        output.attachProcessingJob(job, now);
        return new CreateClipResponse(toSummary(output), jobSummary);
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
        ensureInspectionJob(asset, now);
        return toSummary(asset);
    }

    @Transactional
    public WorkerClipAuthorizationResponse authorizeWorkerClip(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerImportAuthorizationRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireClipJob(worker, jobId);
        ClipPayload payload = clipPayload(job);
        MediaAsset output = requireOutputAssetForClipJob(job);
        MediaAsset source = assets.findByWorkspaceAndId(worker.getWorkspace(), payload.sourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source asset not found"));
        validateClipJobReferences(job, source, output, payload);
        if (source.getStatus() != MediaAssetStatus.READY || source.getStorageKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset is not ready");
        }
        Instant now = Instant.now(clock);
        output.markProcessing(now);
        StorageAccess sourceAccess = storage.presignedGet(source.getStorageKey());
        StorageAccess outputAccess = storage.presignedPut(storage.objectKey(output));
        return new WorkerClipAuthorizationResponse(
                source.getId(),
                output.getId(),
                sourceAccess.url(),
                outputAccess.url(),
                outputAccess.bucket(),
                outputAccess.key(),
                mediaProperties.getMaxDownloadSizeBytes(),
                (int) mediaProperties.getConnectTimeout().toSeconds(),
                (int) mediaProperties.getReadTimeout().toSeconds(),
                payload.startMs(),
                payload.durationMs());
    }

    @Transactional
    public WorkerClipAuthorizationResponse authorizeWorkerSocialVertical(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerImportAuthorizationRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireSocialVerticalJob(worker, jobId);
        DerivativePayload payload = derivativePayload(job);
        MediaAsset output = requireOutputAssetForClipJob(job);
        MediaAsset source = assets.findByWorkspaceAndId(worker.getWorkspace(), payload.sourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source asset not found"));
        validateSocialVerticalJobReferences(job, source, output, payload);
        validateSocialVerticalSource(source);
        if (source.getStorageKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset is not ready");
        }
        Instant now = Instant.now(clock);
        output.markProcessing(now);
        StorageAccess sourceAccess = storage.presignedGet(source.getStorageKey());
        StorageAccess outputAccess = storage.presignedPut(storage.objectKey(output));
        return new WorkerClipAuthorizationResponse(
                source.getId(),
                output.getId(),
                sourceAccess.url(),
                outputAccess.url(),
                outputAccess.bucket(),
                outputAccess.key(),
                mediaProperties.getMaxDownloadSizeBytes(),
                (int) mediaProperties.getConnectTimeout().toSeconds(),
                (int) mediaProperties.getReadTimeout().toSeconds(),
                0,
                0);
    }

    @Transactional
    public MediaAssetSummary completeWorkerClip(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerClipCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireClipJob(worker, jobId);
        ClipPayload payload = clipPayload(job);
        MediaAsset output = requireOutputAssetForClipJob(job);
        MediaAsset source = assets.findByWorkspaceAndId(worker.getWorkspace(), payload.sourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source asset not found"));
        validateClipJobReferences(job, source, output, payload);
        if (!source.getId().equals(request.sourceAssetId()) || !output.getId().equals(request.outputAssetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset does not match clip job");
        }
        validateCompletionMetadata(request.metadata());
        Instant now = Instant.now(clock);
        String key = storage.objectKey(output);
        long objectSize = storage.objectSize(key);
        if (objectSize != request.fileSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stored clip size does not match completion metadata");
        }
        output.markReady(request.metadata(), storage.bucket(), key, now);
        job.complete(worker, Map.of(
                "sourceAssetId", source.getId().toString(),
                "outputAssetId", output.getId().toString(),
                "checksumSha256", request.checksumSha256(),
                "fileSizeBytes", request.fileSizeBytes()), now);
        ensureInspectionJob(output, now);
        return toSummary(output);
    }

    @Transactional
    public MediaAssetSummary completeWorkerSocialVertical(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerClipCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireSocialVerticalJob(worker, jobId);
        DerivativePayload payload = derivativePayload(job);
        MediaAsset output = requireOutputAssetForClipJob(job);
        MediaAsset source = assets.findByWorkspaceAndId(worker.getWorkspace(), payload.sourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source asset not found"));
        validateSocialVerticalJobReferences(job, source, output, payload);
        if (!source.getId().equals(request.sourceAssetId()) || !output.getId().equals(request.outputAssetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset does not match social vertical job");
        }
        validateCompletionMetadata(request.metadata());
        Instant now = Instant.now(clock);
        String key = storage.objectKey(output);
        long objectSize = storage.objectSize(key);
        if (objectSize != request.fileSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stored derivative size does not match completion metadata");
        }
        output.markReady(request.metadata(), storage.bucket(), key, now);
        job.complete(worker, Map.of(
                "sourceAssetId", source.getId().toString(),
                "outputAssetId", output.getId().toString(),
                "checksumSha256", request.checksumSha256(),
                "fileSizeBytes", request.fileSizeBytes()), now);
        ensureInspectionJob(output, now);
        return toSummary(output);
    }

    @Transactional
    public MediaAssetSummary failWorkerClip(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerClipFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireClipJob(worker, jobId);
        ClipPayload payload = clipPayload(job);
        MediaAsset output = requireOutputAssetForClipJob(job);
        MediaAsset source = assets.findByWorkspaceAndId(worker.getWorkspace(), payload.sourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source asset not found"));
        validateClipJobReferences(job, source, output, payload);
        if (!source.getId().equals(request.sourceAssetId()) || !output.getId().equals(request.outputAssetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset does not match clip job");
        }
        Instant now = Instant.now(clock);
        if (Boolean.TRUE.equals(request.terminal())) {
            job.failTerminal(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            output.markFailed(request.errorCode(), safeErrorMessage(request.errorMessage()), now);
        } else {
            job.fail(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            if (job.getStatus() == JobStatus.FAILED) {
                output.markFailed(request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            } else {
                output.markProcessingPendingForRetry(now);
            }
        }
        return toSummary(output);
    }

    @Transactional
    public MediaAssetSummary failWorkerSocialVertical(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerClipFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireSocialVerticalJob(worker, jobId);
        DerivativePayload payload = derivativePayload(job);
        MediaAsset output = requireOutputAssetForClipJob(job);
        MediaAsset source = assets.findByWorkspaceAndId(worker.getWorkspace(), payload.sourceAssetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source asset not found"));
        validateSocialVerticalJobReferences(job, source, output, payload);
        if (!source.getId().equals(request.sourceAssetId()) || !output.getId().equals(request.outputAssetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset does not match social vertical job");
        }
        Instant now = Instant.now(clock);
        if (Boolean.TRUE.equals(request.terminal())) {
            job.failTerminal(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            output.markFailed(request.errorCode(), safeErrorMessage(request.errorMessage()), now);
        } else {
            job.fail(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            if (job.getStatus() == JobStatus.FAILED) {
                output.markFailed(request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            } else {
                output.markProcessingPendingForRetry(now);
            }
        }
        return toSummary(output);
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

    @Transactional
    public WorkerInspectionAuthorizationResponse authorizeWorkerInspection(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerImportAuthorizationRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireInspectionJob(worker, jobId);
        MediaAsset asset = requireAssetForInspectionJob(job);
        validateJobReferencesAsset(job, asset);
        if (asset.getStatus() != MediaAssetStatus.READY || asset.getStorageKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not ready");
        }
        Instant now = Instant.now(clock);
        asset.markInspecting(now);
        StorageAccess access = storage.presignedGet(asset.getStorageKey());
        return new WorkerInspectionAuthorizationResponse(
                asset.getId(),
                access.url(),
                mediaProperties.getMaxDownloadSizeBytes(),
                (int) mediaProperties.getConnectTimeout().toSeconds(),
                (int) mediaProperties.getReadTimeout().toSeconds());
    }

    @Transactional
    public MediaAssetSummary completeWorkerInspection(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerInspectionCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireInspectionJob(worker, jobId);
        MediaAsset asset = requireAssetForInspectionJob(job);
        if (!asset.getId().equals(request.assetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset does not match inspection job");
        }
        validateInspectionMetadata(request.metadata());
        Instant now = Instant.now(clock);
        asset.markInspected(request.metadata(), now);
        job.complete(worker, Map.of(
                "assetId", asset.getId().toString(),
                "inspected", true), now);
        return toSummary(asset);
    }

    @Transactional
    public MediaAssetSummary failWorkerInspection(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerInspectionFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requireInspectionJob(worker, jobId);
        MediaAsset asset = requireAssetForInspectionJob(job);
        if (!asset.getId().equals(request.assetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset does not match inspection job");
        }
        Instant now = Instant.now(clock);
        if (Boolean.TRUE.equals(request.terminal())) {
            job.failTerminal(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            asset.markInspectionFailed(request.errorCode(), safeErrorMessage(request.errorMessage()), now);
        } else {
            job.fail(worker, request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            if (job.getStatus() == JobStatus.FAILED) {
                asset.markInspectionFailed(request.errorCode(), safeErrorMessage(request.errorMessage()), now);
            } else {
                asset.markInspectionPendingForRetry(now);
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

    private Job requireInspectionJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.INSPECT_MEDIA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a media inspection");
        }
        if (job.getStatus() != JobStatus.RUNNING && job.getStatus() != JobStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not active");
        }
        return job;
    }

    private Job requireClipJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.CREATE_CLIP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a clip creation");
        }
        if (job.getStatus() != JobStatus.RUNNING && job.getStatus() != JobStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not active");
        }
        return job;
    }

    private Job requireSocialVerticalJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.CREATE_SOCIAL_VERTICAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a social vertical creation");
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

    private MediaAsset requireAssetForInspectionJob(Job job) {
        return assets.findByInspectionJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
    }

    private MediaAsset requireOutputAssetForClipJob(Job job) {
        return assets.findByProcessingJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Output asset not found"));
    }

    private void validateJobReferencesAsset(Job job, MediaAsset asset) {
        Object payloadAssetId = job.getPayload().get("assetId");
        if (!asset.getId().toString().equals(String.valueOf(payloadAssetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job payload does not match asset");
        }
    }

    private void validateClipJobReferences(Job job, MediaAsset source, MediaAsset output, ClipPayload payload) {
        if (!source.getId().equals(payload.sourceAssetId()) || !output.getId().equals(payload.outputAssetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job payload does not match assets");
        }
        if (output.getParentAsset() == null || !output.getParentAsset().getId().equals(source.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Output asset does not belong to source");
        }
        if (output.getDerivationType() != MediaDerivationType.CLIP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Output asset is not a clip derivative");
        }
        validateClipTiming(source, payload.startMs(), payload.durationMs());
    }

    private void validateSocialVerticalJobReferences(Job job, MediaAsset source, MediaAsset output, DerivativePayload payload) {
        if (!source.getId().equals(payload.sourceAssetId()) || !output.getId().equals(payload.outputAssetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job payload does not match assets");
        }
        if (output.getParentAsset() == null || !output.getParentAsset().getId().equals(source.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Output asset does not belong to source");
        }
        if (output.getDerivationType() != MediaDerivationType.SOCIAL_VERTICAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Output asset is not a social vertical derivative");
        }
    }

    private void validateSocialVerticalSource(MediaAsset source) {
        if (source.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset is not ready");
        }
        if (source.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset is not inspected");
        }
        if (!Boolean.TRUE.equals(source.getHasVideo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset must contain video");
        }
        if (source.getWidth() == null || source.getWidth() <= 0 || source.getHeight() == null || source.getHeight() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source asset dimensions are not known");
        }
    }

    private void validateCompletionMetadata(WorkerImportCompletionRequest request) {
        validateCompletionMetadata(request.metadata());
    }

    private void validateCompletionMetadata(MediaImportMetadata metadata) {
        if (metadata.fileSizeBytes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Imported media is empty");
        }
        if (!SHA_256.matcher(metadata.checksumSha256()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media checksum");
        }
    }

    private void validateClipTiming(MediaAsset source, Long startMs, Long durationMs) {
        if (startMs == null || durationMs == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clip timing is required");
        }
        if (startMs < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startMs must be non-negative");
        }
        if (durationMs <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "durationMs must be positive");
        }
        long endMs;
        try {
            endMs = Math.addExact(startMs, durationMs);
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clip timing is too large");
        }
        Long sourceDuration = source.getDurationMs();
        if (sourceDuration != null) {
            if (startMs >= sourceDuration) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startMs must be before source duration");
            }
            if (endMs > sourceDuration) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clip extends beyond source duration");
            }
        }
    }

    private void validateInspectionMetadata(MediaInspectionMetadata metadata) {
        if (!Boolean.TRUE.equals(metadata.hasVideo()) && !Boolean.TRUE.equals(metadata.hasAudio())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inspection metadata must contain a media stream");
        }
        if (metadata.durationMs() != null && metadata.durationMs() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid duration");
        }
        if (metadata.width() != null && metadata.width() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid width");
        }
        if (metadata.height() != null && metadata.height() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid height");
        }
        if (metadata.frameRate() != null && metadata.frameRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid frame rate");
        }
        if (metadata.bitrate() != null && metadata.bitrate() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bitrate");
        }
        validateShortMetadata(metadata.videoCodec(), "video codec");
        validateShortMetadata(metadata.audioCodec(), "audio codec");
        validateShortMetadata(metadata.containerFormat(), "container format");
    }

    private void validateShortMetadata(String value, String field) {
        if (value != null && (value.isBlank() || value.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field);
        }
    }

    private void ensureInspectionJob(MediaAsset asset, Instant now) {
        Job inspectionJob = asset.getInspectionJob();
        if (inspectionJob != null && !inspectionJob.getStatus().isTerminal()) {
            return;
        }
        JobSummary summary = jobService.createForWorkspace(
                asset.getWorkspace(),
                new JobCreateRequest(JobType.INSPECT_MEDIA, Map.of("assetId", asset.getId().toString())));
        Job job = jobService.getJobEntityForWorkspace(asset.getWorkspace(), summary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Inspection job was not created"));
        asset.attachInspectionJob(job, now);
    }

    private ClipPayload clipPayload(Job job) {
        Map<String, Object> payload = job.getPayload();
        return new ClipPayload(
                UUID.fromString(String.valueOf(payload.get("sourceAssetId"))),
                UUID.fromString(String.valueOf(payload.get("outputAssetId"))),
                longPayload(payload.get("startMs"), "startMs"),
                longPayload(payload.get("durationMs"), "durationMs"));
    }

    private DerivativePayload derivativePayload(Job job) {
        Map<String, Object> payload = job.getPayload();
        return new DerivativePayload(
                UUID.fromString(String.valueOf(payload.get("sourceAssetId"))),
                UUID.fromString(String.valueOf(payload.get("outputAssetId"))));
    }

    private long longPayload(Object value, String field) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid " + field + " in job payload");
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid " + field + " in job payload");
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
        Job inspectionJob = asset.getInspectionJob();
        Job processingJob = asset.getProcessingJob();
        MediaAsset parent = asset.getParentAsset();
        return new MediaAssetSummary(
                asset.getId(),
                asset.getSourceType(),
                asset.getSourceUrl(),
                parent == null ? null : parent.getId(),
                asset.getDerivationType(),
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
                processingJob == null ? null : processingJob.getId(),
                asset.getInspectionStatus(),
                inspectionJob == null ? null : inspectionJob.getId(),
                asset.getInspectionErrorCode(),
                asset.getInspectionErrorMessage(),
                asset.getFrameRate(),
                asset.getBitrate(),
                asset.getHasVideo(),
                asset.getHasAudio(),
                asset.getErrorCode(),
                asset.getErrorMessage(),
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                asset.getReadyAt());
    }

    private record ClipPayload(UUID sourceAssetId, UUID outputAssetId, long startMs, long durationMs) {
    }

    private record DerivativePayload(UUID sourceAssetId, UUID outputAssetId) {
    }
}
