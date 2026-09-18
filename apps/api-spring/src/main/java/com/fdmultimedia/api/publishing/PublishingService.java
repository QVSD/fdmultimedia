package com.fdmultimedia.api.publishing;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
import com.fdmultimedia.api.accounts.SocialAccountStatus;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.assets.MediaInspectionStatus;
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
public class PublishingService {

    private static final int MAX_CAPTION_LENGTH = 2200;
    private static final int MAX_PROVIDER_ID_LENGTH = 200;

    private final AuthService authService;
    private final MediaAssetRepository assets;
    private final SocialAccountRepository socialAccounts;
    private final PublicationRepository publications;
    private final PublishingAttemptRepository attempts;
    private final JobService jobService;
    private final ObjectStorageService storage;
    private final PublishingProperties properties;
    private final Clock clock;

    public PublishingService(
            AuthService authService,
            MediaAssetRepository assets,
            SocialAccountRepository socialAccounts,
            PublicationRepository publications,
            PublishingAttemptRepository attempts,
            JobService jobService,
            ObjectStorageService storage,
            PublishingProperties properties,
            Clock clock) {
        this.authService = authService;
        this.assets = assets;
        this.socialAccounts = socialAccounts;
        this.publications = publications;
        this.attempts = attempts;
        this.jobService = jobService;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public PublicationSummary createPublication(AuthenticatedUser principal, UUID assetId, CreatePublicationRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        SocialAccount account = socialAccounts.findByWorkspaceAndId(workspace, request.socialAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Social account not found"));
        validateAssetEligibility(asset);
        validateAccountEligibility(account);
        String caption = validateCaption(request.caption());

        Instant now = Instant.now(clock);
        Publication publication = publications.save(new Publication(
                workspace, asset, account, caption, membership.getUser(), now));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("publicationId", publication.getId().toString());
        payload.put("assetId", asset.getId().toString());
        payload.put("socialAccountId", account.getId().toString());
        JobSummary jobSummary = jobService.createForWorkspace(
                workspace, new JobCreateRequest(JobType.PUBLISH_MEDIA, payload));
        Job job = jobService.getJobEntityForWorkspace(workspace, jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Publish job was not created"));
        publication.attachJob(job, now);
        return toSummary(publication);
    }

    @Transactional(readOnly = true)
    public List<PublicationSummary> listFor(AuthenticatedUser principal, UUID assetIdFilter) {
        Workspace workspace = currentWorkspace(principal);
        List<Publication> rows;
        if (assetIdFilter != null) {
            MediaAsset asset = assets.findByWorkspaceAndId(workspace, assetIdFilter)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
            rows = publications.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, asset);
        } else {
            rows = publications.findByWorkspaceOrderByCreatedAtDesc(workspace);
        }
        return rows.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public PublicationSummary getFor(AuthenticatedUser principal, UUID publicationId) {
        Workspace workspace = currentWorkspace(principal);
        return publications.findByWorkspaceAndId(workspace, publicationId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publication not found"));
    }

    @Transactional
    public WorkerPublicationAuthorizationResponse authorizeWorkerPublication(
            WorkerPrincipal principal,
            UUID jobId,
            String machineIdentifier) {
        Worker worker = jobService.requireOnlineWorker(principal, machineIdentifier);
        Job job = requirePublishJob(worker, jobId);
        Publication publication = requirePublicationForJob(job);
        MediaAsset asset = publication.getAsset();
        SocialAccount account = publication.getSocialAccount();
        validateJobReferencesPublication(job, publication);
        validateAssetEligibility(asset);
        validateAccountEligibility(account);
        if (asset.getStorageKey() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not stored");
        }
        Instant now = Instant.now(clock);
        publication.markPublishing(now);
        StorageAccess access = storage.presignedGet(asset.getStorageKey());
        return new WorkerPublicationAuthorizationResponse(
                publication.getId(),
                asset.getId(),
                account.getId(),
                account.getPlatform(),
                publication.getCaption(),
                access.url(),
                asset.getChecksumSha256(),
                properties.getMaxDownloadSizeBytes(),
                (int) properties.getConnectTimeout().toSeconds(),
                (int) properties.getReadTimeout().toSeconds(),
                publication.getId().toString());
    }

    @Transactional
    public PublicationSummary completeWorkerPublication(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerPublicationCompletionRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requirePublishJob(worker, jobId);
        Publication publication = requirePublicationForJob(job);
        validateRequestMatchesPublication(publication, request.publicationId(), request.assetId(), request.socialAccountId());
        String providerRequestId = validateProviderId(request.providerRequestId(), "providerRequestId");
        String providerPublicationId = validateProviderId(request.providerPublicationId(), "providerPublicationId");
        Instant now = Instant.now(clock);
        Instant publishedAt = validatePublishedAt(request.publishedAt(), now);
        int attemptNumber = job.getAttemptCount();
        Instant startedAt = job.getStartedAt();
        publication.markPublished(providerRequestId, providerPublicationId, publishedAt, now);
        jobService.completeOwnedJob(job, worker, Map.of(
                "publicationId", publication.getId().toString(),
                "providerPublicationId", providerPublicationId), now);
        attempts.save(new PublishingAttempt(
                publication, job, attemptNumber, worker, startedAt, now,
                PublishingAttemptOutcome.SUCCEEDED, providerRequestId, providerPublicationId, null, null));
        return toSummary(publication);
    }

    @Transactional
    public PublicationSummary failWorkerPublication(
            WorkerPrincipal principal,
            UUID jobId,
            WorkerPublicationFailureRequest request) {
        Worker worker = jobService.requireOnlineWorker(principal, request.machineIdentifier());
        Job job = requirePublishJob(worker, jobId);
        Publication publication = requirePublicationForJob(job);
        validateRequestMatchesPublication(publication, request.publicationId(), request.assetId(), request.socialAccountId());
        Instant now = Instant.now(clock);
        int attemptNumber = job.getAttemptCount();
        Instant startedAt = job.getStartedAt();
        String errorCode = request.errorCode();
        String errorMessage = safeErrorMessage(request.errorMessage());
        boolean terminal = Boolean.TRUE.equals(request.terminal());
        PublishingAttemptOutcome outcome;
        if (terminal) {
            jobService.failOwnedJob(job, worker, errorCode, errorMessage, true, now);
            publication.markFailed(errorCode, errorMessage, now);
            outcome = PublishingAttemptOutcome.FAILED;
        } else {
            jobService.failOwnedJob(job, worker, errorCode, errorMessage, false, now);
            if (job.getStatus() == JobStatus.FAILED) {
                publication.markFailed(errorCode, errorMessage, now);
                outcome = PublishingAttemptOutcome.FAILED;
            } else {
                publication.markPendingForRetry(now);
                outcome = PublishingAttemptOutcome.RETRYABLE_FAILED;
            }
        }
        attempts.save(new PublishingAttempt(
                publication, job, attemptNumber, worker, startedAt, now,
                outcome, null, null, errorCode, errorMessage));
        return toSummary(publication);
    }

    private Job requirePublishJob(Worker worker, UUID jobId) {
        Job job = jobService.requireJobForWorkerWorkspace(worker, jobId);
        if (job.getType() != JobType.PUBLISH_MEDIA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a media publication");
        }
        if (job.getStatus() != JobStatus.RUNNING && job.getStatus() != JobStatus.ASSIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not active");
        }
        return job;
    }

    private Publication requirePublicationForJob(Job job) {
        return publications.findByJobId(job.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Publication not found"));
    }

    private void validateJobReferencesPublication(Job job, Publication publication) {
        Map<String, Object> payload = job.getPayload();
        if (!publication.getId().toString().equals(String.valueOf(payload.get("publicationId")))
                || !publication.getAsset().getId().toString().equals(String.valueOf(payload.get("assetId")))
                || !publication.getSocialAccount().getId().toString().equals(String.valueOf(payload.get("socialAccountId")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job payload does not match publication");
        }
    }

    private void validateRequestMatchesPublication(Publication publication, UUID publicationId, UUID assetId, UUID socialAccountId) {
        if (!publication.getId().equals(publicationId)
                || !publication.getAsset().getId().equals(assetId)
                || !publication.getSocialAccount().getId().equals(socialAccountId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request does not match publication");
        }
    }

    private void validateAssetEligibility(MediaAsset asset) {
        if (asset.getStatus() != MediaAssetStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not ready");
        }
        if (asset.getInspectionStatus() != MediaInspectionStatus.INSPECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not inspected");
        }
        if (!Boolean.TRUE.equals(asset.getHasVideo())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset must contain video");
        }
    }

    private void validateAccountEligibility(SocialAccount account) {
        if (account.getStatus() != SocialAccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Social account is not active");
        }
        if (account.getPlatform() != SocialPlatform.TEST) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Platform is not yet supported");
        }
    }

    private String validateCaption(String caption) {
        if (caption == null) {
            return null;
        }
        String trimmed = caption.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_CAPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "caption must be at most " + MAX_CAPTION_LENGTH + " characters");
        }
        return trimmed;
    }

    private String validateProviderId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_PROVIDER_ID_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
        }
        return trimmed;
    }

    private Instant validatePublishedAt(Instant publishedAt, Instant now) {
        if (publishedAt == null) {
            return now;
        }
        Instant earliest = now.minusSeconds(3600);
        Instant latest = now.plusSeconds(60);
        if (publishedAt.isBefore(earliest) || publishedAt.isAfter(latest)) {
            return now;
        }
        return publishedAt;
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private String safeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Publishing failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private PublicationSummary toSummary(Publication publication) {
        Job job = publication.getJob();
        List<PublishingAttemptSummary> attemptSummaries = attempts.findByPublicationOrderByJobAttemptAsc(publication).stream()
                .map(this::toSummary)
                .toList();
        return new PublicationSummary(
                publication.getId(),
                publication.getAsset().getId(),
                publication.getAsset().getOriginalFilename(),
                publication.getSocialAccount().getId(),
                publication.getSocialAccount().getDisplayName(),
                publication.getSocialAccount().getPlatform(),
                publication.getStatus(),
                job == null ? null : job.getId(),
                publication.getCaption(),
                publication.getProviderRequestId(),
                publication.getProviderPublicationId(),
                publication.getCreatedAt(),
                publication.getUpdatedAt(),
                publication.getPublishedAt(),
                publication.getFailureCode(),
                publication.getFailureMessage(),
                attemptSummaries);
    }

    private PublishingAttemptSummary toSummary(PublishingAttempt attempt) {
        Worker worker = attempt.getWorker();
        return new PublishingAttemptSummary(
                attempt.getId(),
                attempt.getJobAttempt(),
                worker == null ? null : worker.getId(),
                worker == null ? null : worker.getName(),
                attempt.getStartedAt(),
                attempt.getFinishedAt(),
                attempt.getOutcome(),
                attempt.getProviderRequestId(),
                attempt.getProviderPublicationId(),
                attempt.getErrorCode(),
                attempt.getErrorMessage());
    }
}
