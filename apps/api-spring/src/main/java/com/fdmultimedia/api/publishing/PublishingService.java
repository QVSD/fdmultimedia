package com.fdmultimedia.api.publishing;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
import com.fdmultimedia.api.accounts.SocialAccountStatus;
import com.fdmultimedia.api.accounts.SocialCredentialMetadata;
import com.fdmultimedia.api.accounts.SocialCredentialService;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.analytics.PublicationAnalyticsStore;
import com.fdmultimedia.api.analytics.PublicationAttributionService;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
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
import com.fdmultimedia.api.publishing.instagram.InstagramDriveOutcome;
import com.fdmultimedia.api.publishing.instagram.InstagramPublishingService;
import com.fdmultimedia.api.publishing.instagram.InstagramProperties;
import com.fdmultimedia.api.publishing.tiktok.*;
import com.fdmultimedia.api.users.AppUser;
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
    private final PublishingEligibilityService eligibilityService;
    private final SocialCredentialService credentialService;
    private final InstagramPublishingService instagramPublishingService;
    private final InstagramProperties instagramProperties;
    private final TikTokPublishingService tiktokPublishingService;
    private final TikTokPublicationSettingsRepository tiktokSettingsRepository;
    private final TikTokProperties tiktokProperties;
    private final Clock clock;
    private final PublicationAttributionService attributionService;
    private final PublicationAnalyticsStore analyticsStore;

    public PublishingService(
            AuthService authService,
            MediaAssetRepository assets,
            SocialAccountRepository socialAccounts,
            PublicationRepository publications,
            PublishingAttemptRepository attempts,
            JobService jobService,
            ObjectStorageService storage,
            PublishingProperties properties,
            PublishingEligibilityService eligibilityService,
            SocialCredentialService credentialService,
            InstagramPublishingService instagramPublishingService,
            InstagramProperties instagramProperties, TikTokPublishingService tiktokPublishingService,
            TikTokPublicationSettingsRepository tiktokSettingsRepository, TikTokProperties tiktokProperties,
            Clock clock,
            PublicationAttributionService attributionService,
            PublicationAnalyticsStore analyticsStore) {
        this.authService = authService;
        this.assets = assets;
        this.socialAccounts = socialAccounts;
        this.publications = publications;
        this.attempts = attempts;
        this.jobService = jobService;
        this.storage = storage;
        this.properties = properties;
        this.eligibilityService = eligibilityService;
        this.credentialService = credentialService;
        this.instagramPublishingService = instagramPublishingService;
        this.instagramProperties = instagramProperties;
        this.tiktokPublishingService = tiktokPublishingService;
        this.tiktokSettingsRepository = tiktokSettingsRepository;
        this.tiktokProperties = tiktokProperties;
        this.clock = clock;
        this.attributionService = attributionService;
        this.analyticsStore = analyticsStore;
    }

    @Transactional
    public PublicationSummary createPublication(AuthenticatedUser principal, UUID assetId, CreatePublicationRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        MediaAsset asset = assets.findByWorkspaceAndId(workspace, assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        SocialAccount account = socialAccounts.findByWorkspaceAndId(workspace, request.socialAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Social account not found"));
        return createPublicationInternal(workspace, asset, account, request.caption(), membership.getUser(), null, null, null, request.tiktokSettings());
    }

    /**
     * Used by {@code ContentDraftService} so Draft-originated publishes reuse
     * every eligibility/account/job-creation rule below rather than
     * duplicating them. The asset and workspace are trusted here because the
     * caller has already resolved them through a workspace-scoped Draft.
     */
    @Transactional
    public PublicationSummary createPublicationForDraft(
            AuthenticatedUser principal, MediaAsset asset, UUID socialAccountId, String caption, UUID contentDraftId,
            TikTokSettingsRequest tiktokSettings) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        SocialAccount account = socialAccounts.findByWorkspaceAndId(workspace, socialAccountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Social account not found"));
        return createPublicationInternal(workspace, asset, account, caption, membership.getUser(), contentDraftId, null, null, tiktokSettings);
    }

    public PublicationSummary createPublicationForDraft(AuthenticatedUser principal, MediaAsset asset,
            UUID socialAccountId, String caption, UUID contentDraftId) {
        return createPublicationForDraft(principal, asset, socialAccountId, caption, contentDraftId, null);
    }

    /**
     * Used by {@code PublishScheduleDispatcher} at due time. There is no
     * {@link AuthenticatedUser} in a background dispatch — the workspace,
     * account, and media are already resolved (and freshly re-validated) by
     * the caller from a locked {@code PublishSchedule} row, and the
     * Publication is attributed to whoever created the schedule.
     */
    @Transactional
    public PublicationSummary createPublicationForSchedule(
            Workspace workspace, MediaAsset asset, SocialAccount account, String caption, UUID contentDraftId,
            AppUser createdByUser, UUID scheduleId, UUID appliedSuggestionId) {
        return createPublicationForSchedule(workspace, asset, account, caption, contentDraftId, createdByUser, scheduleId, appliedSuggestionId, null);
    }

    public PublicationSummary createPublicationForSchedule(
            Workspace workspace, MediaAsset asset, SocialAccount account, String caption, UUID contentDraftId,
            AppUser createdByUser, UUID scheduleId, UUID appliedSuggestionId, TikTokSettingsRequest tiktokSettings) {
        return createPublicationInternal(workspace, asset, account, caption, createdByUser,
                contentDraftId, scheduleId, appliedSuggestionId, tiktokSettings);
    }

    private PublicationSummary createPublicationInternal(
            Workspace workspace, MediaAsset asset, SocialAccount account, String rawCaption, AppUser user,
            UUID contentDraftId, UUID scheduleId, UUID appliedSuggestionId, TikTokSettingsRequest tiktokSettings) {
        eligibilityService.validateAssetEligibility(asset, account.getPlatform());
        validateAccountEligibility(account);
        String caption = validateCaption(rawCaption);

        Instant now = Instant.now(clock);
        Publication publication = publications.save(new Publication(
                workspace, asset, account, caption, user, contentDraftId, now));
        if (account.getPlatform() == SocialPlatform.TIKTOK) {
            if (tiktokSettings == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TikTok publishing settings must be selected explicitly");
            }
            tiktokSettingsRepository.save(new TikTokPublicationSettings(publication, tiktokSettings, now));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("publicationId", publication.getId().toString());
        payload.put("assetId", asset.getId().toString());
        payload.put("socialAccountId", account.getId().toString());
        payload.put("provider", account.getPlatform().name());
        JobSummary jobSummary = jobService.createForWorkspace(
                workspace, new JobCreateRequest(JobType.PUBLISH_MEDIA, payload));
        Job job = jobService.getJobEntityForWorkspace(workspace, jobSummary.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Publish job was not created"));
        publication.attachJob(job, now);
        // Attribution uses JDBC in the same transaction; flush JPA's insert so
        // the publication FK is visible before that immutable row is written.
        publications.flush();
        attributionService.capture(publication, scheduleId, appliedSuggestionId, now);
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
    public List<PublicationSummary> listForContentDraft(Workspace workspace, UUID contentDraftId) {
        return publications.findByWorkspaceAndContentDraftIdOrderByCreatedAtDesc(workspace, contentDraftId).stream()
                .map(this::toSummary)
                .toList();
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
        eligibilityService.validateAssetEligibility(asset, account.getPlatform());
        validateAccountEligibility(account);
        Instant now = Instant.now(clock);
        publication.markPublishing(now);
        // Only the TEST provider path downloads media through the Worker; a
        // real Instagram publish never gives the Worker a presigned storage
        // URL, so this is skipped for every other platform.
        String downloadUrl = null;
        if (account.getPlatform() == SocialPlatform.TEST) {
            if (asset.getStorageKey() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is not stored");
            }
            StorageAccess access = storage.presignedGet(asset.getStorageKey());
            downloadUrl = access.url();
        }
        return new WorkerPublicationAuthorizationResponse(
                publication.getId(),
                asset.getId(),
                account.getId(),
                account.getPlatform(),
                publication.getCaption(),
                downloadUrl,
                asset.getChecksumSha256(),
                properties.getMaxDownloadSizeBytes(),
                (int) properties.getConnectTimeout().toSeconds(),
                (int) properties.getReadTimeout().toSeconds(),
                publication.getId().toString());
    }

    /**
     * One bounded step of driving an Instagram publish forward, called
     * repeatedly by the Worker's poll loop. Unlike the TEST provider's
     * separate authorize/complete/fail calls, this endpoint performs
     * completion/failure bookkeeping itself the moment the outcome becomes
     * final, since all Instagram provider state (and the credential needed to
     * inspect it) lives only on the backend.
     */
    @Transactional
    public PublicationDriveResponse driveInstagramPublication(WorkerPrincipal principal, UUID jobId, String machineIdentifier) {
        Worker worker = jobService.requireOnlineWorker(principal, machineIdentifier);
        Job job = requirePublishJob(worker, jobId);
        Publication publication = requirePublicationForJob(job);
        validateJobReferencesPublication(job, publication);
        if (publication.getSocialAccount().getPlatform() != SocialPlatform.INSTAGRAM) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not an Instagram publication");
        }
        if (publication.getStatus() != PublicationStatus.PUBLISHING) {
            publication.markPublishing(Instant.now(clock));
        }

        InstagramDriveOutcome outcome = instagramPublishingService.drive(publication);
        Instant now = Instant.now(clock);
        int attemptNumber = job.getAttemptCount();
        Instant startedAt = job.getStartedAt();

        return switch (outcome.status()) {
            case IN_PROGRESS -> new PublicationDriveResponse("IN_PROGRESS");
            case PUBLISHED -> {
                completePublicationInternal(job, worker, publication, attemptNumber, startedAt,
                        outcome.providerRequestId(), outcome.providerPublicationId(), now);
                yield new PublicationDriveResponse("PUBLISHED");
            }
            case FAILED -> {
                failPublicationInternal(job, worker, publication, attemptNumber, startedAt,
                        outcome.errorCode(), outcome.errorMessage(), outcome.terminal(), now);
                yield new PublicationDriveResponse("FAILED");
            }
        };
    }

    @Transactional
    public PublicationDriveResponse driveTikTokPublication(WorkerPrincipal principal, UUID jobId, String machineIdentifier) {
        Worker worker = jobService.requireOnlineWorker(principal, machineIdentifier);
        Job job = requirePublishJob(worker, jobId);
        Publication publication = requirePublicationForJob(job);
        validateJobReferencesPublication(job, publication);
        if (publication.getSocialAccount().getPlatform() != SocialPlatform.TIKTOK) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not a TikTok publication");
        }
        if (publication.getStatus() != PublicationStatus.PUBLISHING) publication.markPublishing(Instant.now(clock));
        InstagramDriveOutcome outcome = tiktokPublishingService.drive(publication);
        Instant now = Instant.now(clock);
        return switch (outcome.status()) {
            case IN_PROGRESS -> new PublicationDriveResponse("IN_PROGRESS");
            case PUBLISHED -> { completePublicationInternal(job, worker, publication, job.getAttemptCount(), job.getStartedAt(), outcome.providerRequestId(), outcome.providerPublicationId(), now); yield new PublicationDriveResponse("PUBLISHED"); }
            case FAILED -> { failPublicationInternal(job, worker, publication, job.getAttemptCount(), job.getStartedAt(), outcome.errorCode(), outcome.errorMessage(), outcome.terminal(), now); yield new PublicationDriveResponse("FAILED"); }
        };
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
        analyticsStore.schedulePublished(publication);
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
        int attemptNumber = job.getAttemptCount();
        Instant startedAt = job.getStartedAt();
        String errorCode = request.errorCode();
        String errorMessage = safeErrorMessage(request.errorMessage());
        boolean terminal = Boolean.TRUE.equals(request.terminal());
        Instant now = Instant.now(clock);
        failPublicationInternal(job, worker, publication, attemptNumber, startedAt, errorCode, errorMessage, terminal, now);
        return toSummary(publication);
    }

    /**
     * Shared completion path for both the TEST provider's HTTP completion
     * call and the Instagram drive loop's internal completion. Unlike
     * {@link #completeWorkerPublication}, {@code providerPublicationId} may be
     * null here: the one documented Instagram API gap (see
     * {@code InstagramPublishingService}) is that a crash between a
     * successful {@code media_publish} call and our own bookkeeping leaves no
     * way to recover the exact media id afterward. The Publication is still
     * completed as PUBLISHED in that case — treating a confirmed real post as
     * a false failure would be worse than an unknown provider id.
     */
    private void completePublicationInternal(
            Job job, Worker worker, Publication publication, int attemptNumber, Instant startedAt,
            String providerRequestId, String providerPublicationId, Instant now) {
        String safeRequestId = trimToNull(providerRequestId, MAX_PROVIDER_ID_LENGTH);
        String safePublicationId = trimToNull(providerPublicationId, MAX_PROVIDER_ID_LENGTH);
        publication.markPublished(safeRequestId, safePublicationId, now, now);
        analyticsStore.schedulePublished(publication);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicationId", publication.getId().toString());
        if (safePublicationId != null) {
            result.put("providerPublicationId", safePublicationId);
        }
        jobService.completeOwnedJob(job, worker, result, now);
        attempts.save(new PublishingAttempt(
                publication, job, attemptNumber, worker, startedAt, now,
                PublishingAttemptOutcome.SUCCEEDED, safeRequestId, safePublicationId, null, null));
    }

    private void failPublicationInternal(
            Job job, Worker worker, Publication publication, int attemptNumber, Instant startedAt,
            String errorCode, String errorMessage, boolean terminal, Instant now) {
        String safeMessage = safeErrorMessage(errorMessage);
        PublishingAttemptOutcome outcome;
        if (terminal) {
            jobService.failOwnedJob(job, worker, errorCode, safeMessage, true, now);
            publication.markFailed(errorCode, safeMessage, now);
            outcome = PublishingAttemptOutcome.FAILED;
        } else {
            jobService.failOwnedJob(job, worker, errorCode, safeMessage, false, now);
            if (job.getStatus() == JobStatus.FAILED) {
                publication.markFailed(errorCode, safeMessage, now);
                outcome = PublishingAttemptOutcome.FAILED;
            } else {
                publication.markPendingForRetry(now);
                outcome = PublishingAttemptOutcome.RETRYABLE_FAILED;
            }
        }
        attempts.save(new PublishingAttempt(
                publication, job, attemptNumber, worker, startedAt, now,
                outcome, null, null, errorCode, safeMessage));
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

    private void validateAccountEligibility(SocialAccount account) {
        if (account.getStatus() != SocialAccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Social account is not active");
        }
        if (account.getPlatform() == SocialPlatform.TEST) {
            return;
        }
        if (account.getPlatform() == SocialPlatform.TIKTOK) {
            if (!tiktokProperties.isConfigured()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TikTok integration is not configured");
            SocialCredentialMetadata credential = credentialService.metadataFor(account);
            if (!credential.present()) throw new ResponseStatusException(HttpStatus.CONFLICT, "TikTok account has no stored credential; reconnect it");
            return;
        }
        if (account.getPlatform() != SocialPlatform.INSTAGRAM) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Platform is not yet supported");
        }
        if (!instagramProperties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instagram integration is not configured");
        }
        SocialCredentialMetadata credential = credentialService.metadataFor(account);
        if (!credential.present()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instagram account has no stored credential; reconnect it");
        }
        if (credential.expired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Instagram credential has expired; reconnect the account");
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

    private String trimToNull(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
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
                publication.getContentDraftId(),
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
