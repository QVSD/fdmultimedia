package com.fdmultimedia.api.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
import com.fdmultimedia.api.accounts.SocialCredentialMetadata;
import com.fdmultimedia.api.accounts.SocialCredentialService;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.analytics.PublicationAnalyticsStore;
import com.fdmultimedia.api.analytics.PublicationAttributionService;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
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
import com.fdmultimedia.api.publishing.instagram.InstagramProperties;
import com.fdmultimedia.api.publishing.instagram.InstagramPublishingService;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class PublishingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final SocialAccountRepository socialAccounts = mock(SocialAccountRepository.class);
    private final PublicationRepository publications = mock(PublicationRepository.class);
    private final PublishingAttemptRepository attempts = mock(PublishingAttemptRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final PublishingProperties properties = new PublishingProperties();
    private final PublishingEligibilityService eligibilityService = new PublishingEligibilityService();
    private final SocialCredentialService credentialService = mock(SocialCredentialService.class);
    private final InstagramPublishingService instagramPublishingService = mock(InstagramPublishingService.class);
    private final InstagramProperties instagramProperties = new InstagramProperties();
    private final PublicationAttributionService attributionService = mock(PublicationAttributionService.class);
    private final PublicationAnalyticsStore analyticsStore = mock(PublicationAnalyticsStore.class);
    private final PublishingService service = new PublishingService(
            authService, assets, socialAccounts, publications, attempts, jobService, storage, properties,
            eligibilityService, credentialService, instagramPublishingService, instagramProperties,
            Clock.fixed(NOW, ZoneOffset.UTC), attributionService, analyticsStore);

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private Worker worker;
    private WorkerPrincipal workerPrincipal;
    private SocialAccount account;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        WorkerCredential credential = new WorkerCredential(UUID.randomUUID(), workspace, "local-agent", "$2a$10$hash");
        worker = new Worker(workspace, credential, registration("machine-1"), NOW.minusSeconds(5));
        workerPrincipal = new WorkerPrincipal(credential);
        when(jobService.requireOnlineWorker(workerPrincipal, "machine-1")).thenReturn(worker);
        account = new SocialAccount(workspace, SocialPlatform.TEST, "My TEST Account", owner, NOW);
        when(publications.save(any(Publication.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(attempts.save(any(PublishingAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(attempts.findByPublicationOrderByJobAttemptAsc(any())).thenReturn(java.util.List.of());
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
    void createsPublicationAndPublishJobAtomicallyForEligibleAsset() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        Job job = publishJob(asset.getId(), account.getId());
        when(jobService.createForWorkspace(any(), any(JobCreateRequest.class))).thenReturn(jobSummary(job));
        when(jobService.getJobEntityForWorkspace(workspace, job.getId())).thenReturn(Optional.of(job));

        PublicationSummary summary = service.createPublication(user, asset.getId(), new CreatePublicationRequest(account.getId(), "Hello"));

        ArgumentCaptor<JobCreateRequest> request = ArgumentCaptor.forClass(JobCreateRequest.class);
        verify(jobService).createForWorkspace(any(), request.capture());
        assertThat(request.getValue().type()).isEqualTo(JobType.PUBLISH_MEDIA);
        assertThat(summary.status()).isEqualTo(PublicationStatus.PENDING);
        assertThat(summary.assetId()).isEqualTo(asset.getId());
        assertThat(summary.socialAccountId()).isEqualTo(account.getId());
        assertThat(summary.caption()).isEqualTo("Hello");
    }

    @Test
    void rejectsPublicationForAssetMissingVideo() {
        MediaAsset asset = readyInspectedAsset(false, true);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.createPublication(user, asset.getId(), new CreatePublicationRequest(account.getId(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsPublicationForUninspectedAsset() {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        asset.markImporting(NOW.minusSeconds(5));
        asset.markReady(new MediaImportMetadata("media.mp4", "video/mp4", 12_000, "0".repeat(64), null, null, null, null, null, "mp4"), "media-assets", "storage-key", NOW.minusSeconds(4));
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.createPublication(user, asset.getId(), new CreatePublicationRequest(account.getId(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsPublicationForInactiveAccount() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount disconnected = new SocialAccount(workspace, SocialPlatform.TEST, "Disconnected", owner, NOW);
        ReflectionTestUtils.setField(disconnected, "status", com.fdmultimedia.api.accounts.SocialAccountStatus.DISCONNECTED);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, disconnected.getId())).thenReturn(Optional.of(disconnected));

        assertThatThrownBy(() -> service.createPublication(user, asset.getId(), new CreatePublicationRequest(disconnected.getId(), null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsPublicationWhenAccountNotFoundInWorkspace() {
        MediaAsset asset = readyInspectedVideoAsset();
        UUID otherAccountId = UUID.randomUUID();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, otherAccountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPublication(user, asset.getId(), new CreatePublicationRequest(otherAccountId, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsPublicationWhenAssetNotFoundInWorkspace() {
        UUID otherAssetId = UUID.randomUUID();
        when(assets.findByWorkspaceAndId(workspace, otherAssetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPublication(user, otherAssetId, new CreatePublicationRequest(account.getId(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createPublicationForDraftLinksContentDraftAndSnapshotsCaptionAtCallTime() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        Job job = publishJob(asset.getId(), account.getId());
        when(jobService.createForWorkspace(any(), any(JobCreateRequest.class))).thenReturn(jobSummary(job));
        when(jobService.getJobEntityForWorkspace(workspace, job.getId())).thenReturn(Optional.of(job));
        UUID draftId = UUID.randomUUID();

        PublicationSummary summary = service.createPublicationForDraft(user, asset, account.getId(), "Caption at publish time", draftId);

        assertThat(summary.contentDraftId()).isEqualTo(draftId);
        assertThat(summary.caption()).isEqualTo("Caption at publish time");
        ArgumentCaptor<Publication> saved = ArgumentCaptor.forClass(Publication.class);
        verify(publications).save(saved.capture());
        assertThat(saved.getValue().getContentDraftId()).isEqualTo(draftId);
        // Later Draft caption edits happen on the ContentDraft entity only, never
        // on this Publication row — the caption above is a copy, not a live link.
        assertThat(saved.getValue().getCaption()).isEqualTo("Caption at publish time");
    }

    @Test
    void directPublicationHasNoContentDraftId() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        Job job = publishJob(asset.getId(), account.getId());
        when(jobService.createForWorkspace(any(), any(JobCreateRequest.class))).thenReturn(jobSummary(job));
        when(jobService.getJobEntityForWorkspace(workspace, job.getId())).thenReturn(Optional.of(job));

        PublicationSummary summary = service.createPublication(user, asset.getId(), new CreatePublicationRequest(account.getId(), "Hello"));

        assertThat(summary.contentDraftId()).isNull();
    }

    @Test
    void rejectsCaptionOverMaxLength() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        String tooLong = "a".repeat(2201);

        assertThatThrownBy(() -> service.createPublication(user, asset.getId(), new CreatePublicationRequest(account.getId(), tooLong)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void authorizesWorkerWithPresignedSourceDownloadAndMarksPublishing() {
        MediaAsset asset = readyInspectedVideoAsset();
        Publication publication = new Publication(workspace, asset, account, "Hello", owner, NOW.minusSeconds(5));
        Job job = new Job(workspace, JobType.PUBLISH_MEDIA, Map.of(
                "publicationId", publication.getId().toString(),
                "assetId", asset.getId().toString(),
                "socialAccountId", account.getId().toString()), 3, NOW);
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        publication.attachJob(job, NOW.minusSeconds(5));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));
        when(storage.presignedGet("storage-key")).thenReturn(new StorageAccess("http://minio/source", "media-assets", "storage-key", NOW.plusSeconds(60)));

        WorkerPublicationAuthorizationResponse response = service.authorizeWorkerPublication(workerPrincipal, job.getId(), "machine-1");

        assertThat(response.downloadUrl()).isEqualTo("http://minio/source");
        assertThat(response.publicationId()).isEqualTo(publication.getId());
        assertThat(response.platform()).isEqualTo(SocialPlatform.TEST);
        assertThat(response.idempotencyKey()).isEqualTo(publication.getId().toString());
        assertThat(publication.getStatus()).isEqualTo(PublicationStatus.PUBLISHING);
    }

    @Test
    void completesPublicationAtomicallyAndRecordsAttempt() {
        MediaAsset asset = readyInspectedVideoAsset();
        Job job = publishJob(asset.getId(), account.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        Publication publication = new Publication(workspace, asset, account, "Hello", owner, NOW.minusSeconds(5));
        publication.attachJob(job, NOW.minusSeconds(5));
        publication.markPublishing(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));

        PublicationSummary summary = service.completeWorkerPublication(
                workerPrincipal,
                job.getId(),
                new WorkerPublicationCompletionRequest(
                        "machine-1", publication.getId(), asset.getId(), account.getId(),
                        "test-req-" + publication.getId(), "test-pub-" + publication.getId(), NOW));

        assertThat(summary.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(summary.providerPublicationId()).isEqualTo("test-pub-" + publication.getId());
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        ArgumentCaptor<PublishingAttempt> attemptCaptor = ArgumentCaptor.forClass(PublishingAttempt.class);
        verify(attempts).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getOutcome()).isEqualTo(PublishingAttemptOutcome.SUCCEEDED);
    }

    @Test
    void rejectsCompletionWhenRequestDoesNotMatchPublication() {
        MediaAsset asset = readyInspectedVideoAsset();
        Job job = publishJob(asset.getId(), account.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        Publication publication = new Publication(workspace, asset, account, "Hello", owner, NOW.minusSeconds(5));
        publication.attachJob(job, NOW.minusSeconds(5));
        publication.markPublishing(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));

        assertThatThrownBy(() -> service.completeWorkerPublication(
                workerPrincipal,
                job.getId(),
                new WorkerPublicationCompletionRequest(
                        "machine-1", UUID.randomUUID(), asset.getId(), account.getId(),
                        "test-req-x", "test-pub-x", NOW)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(publication.getStatus()).isEqualTo(PublicationStatus.PUBLISHING);
    }

    @Test
    void retryableFailureRequeuesSamePublicationForRetry() {
        MediaAsset asset = readyInspectedVideoAsset();
        Job job = publishJob(asset.getId(), account.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        Publication publication = new Publication(workspace, asset, account, "Hello", owner, NOW.minusSeconds(5));
        publication.attachJob(job, NOW.minusSeconds(5));
        publication.markPublishing(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));

        PublicationSummary summary = service.failWorkerPublication(
                workerPrincipal,
                job.getId(),
                new WorkerPublicationFailureRequest(
                        "machine-1", publication.getId(), asset.getId(), account.getId(),
                        "TRANSIENT", "temporary provider error", false));

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(summary.status()).isEqualTo(PublicationStatus.PENDING);
        assertThat(publication.getId()).isEqualTo(summary.id());
        ArgumentCaptor<PublishingAttempt> attemptCaptor = ArgumentCaptor.forClass(PublishingAttempt.class);
        verify(attempts).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getOutcome()).isEqualTo(PublishingAttemptOutcome.RETRYABLE_FAILED);
    }

    @Test
    void terminalFailureMarksPublicationFailedWithoutFurtherRetries() {
        MediaAsset asset = readyInspectedVideoAsset();
        Job job = publishJob(asset.getId(), account.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        Publication publication = new Publication(workspace, asset, account, "Hello", owner, NOW.minusSeconds(5));
        publication.attachJob(job, NOW.minusSeconds(5));
        publication.markPublishing(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));

        PublicationSummary summary = service.failWorkerPublication(
                workerPrincipal,
                job.getId(),
                new WorkerPublicationFailureRequest(
                        "machine-1", publication.getId(), asset.getId(), account.getId(),
                        "PUBLISH_UNSUPPORTED_PLATFORM", "cannot publish", true));

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.status()).isEqualTo(PublicationStatus.FAILED);
        assertThat(summary.failureCode()).isEqualTo("PUBLISH_UNSUPPORTED_PLATFORM");
    }

    @Test
    void exhaustingRetriesMarksPublicationFailed() {
        MediaAsset asset = readyInspectedVideoAsset();
        Job job = new Job(workspace, JobType.PUBLISH_MEDIA, publishPayload(asset.getId(), account.getId()), 1, NOW.minusSeconds(10));
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        Publication publication = new Publication(workspace, asset, account, "Hello", owner, NOW.minusSeconds(5));
        publication.attachJob(job, NOW.minusSeconds(5));
        publication.markPublishing(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));

        PublicationSummary summary = service.failWorkerPublication(
                workerPrincipal,
                job.getId(),
                new WorkerPublicationFailureRequest(
                        "machine-1", publication.getId(), asset.getId(), account.getId(),
                        "TRANSIENT", "temporary provider error", false));

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(summary.status()).isEqualTo(PublicationStatus.FAILED);
    }

    @Test
    void createPublicationJobPayloadRecordsProviderForCapabilityGating() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        Job job = publishJob(asset.getId(), account.getId());
        when(jobService.createForWorkspace(any(), any(JobCreateRequest.class))).thenReturn(jobSummary(job));
        when(jobService.getJobEntityForWorkspace(workspace, job.getId())).thenReturn(Optional.of(job));

        service.createPublication(user, asset.getId(), new CreatePublicationRequest(account.getId(), null));

        ArgumentCaptor<JobCreateRequest> request = ArgumentCaptor.forClass(JobCreateRequest.class);
        verify(jobService).createForWorkspace(any(), request.capture());
        assertThat(request.getValue().payload().get("provider")).isEqualTo("TEST");
    }

    @Test
    void instagramAccountRejectedWhenIntegrationNotConfigured() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, instagramAccount.getId())).thenReturn(Optional.of(instagramAccount));
        instagramProperties.setEnabled(false);

        assertThatThrownBy(() -> service.createPublication(user, asset.getId(), new CreatePublicationRequest(instagramAccount.getId(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void instagramAccountRejectedWithoutStoredCredential() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, instagramAccount.getId())).thenReturn(Optional.of(instagramAccount));
        configureInstagram();
        when(credentialService.metadataFor(instagramAccount)).thenReturn(SocialCredentialMetadata.absent());

        assertThatThrownBy(() -> service.createPublication(user, asset.getId(), new CreatePublicationRequest(instagramAccount.getId(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void instagramAccountRejectedWithExpiredCredential() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, instagramAccount.getId())).thenReturn(Optional.of(instagramAccount));
        configureInstagram();
        when(credentialService.metadataFor(instagramAccount))
                .thenReturn(new SocialCredentialMetadata(true, NOW.minusSeconds(1), true, "scopes", NOW.minusSeconds(100)));

        assertThatThrownBy(() -> service.createPublication(user, asset.getId(), new CreatePublicationRequest(instagramAccount.getId(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void instagramAccountAcceptedWithActiveCredentialAndConfiguredIntegration() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, instagramAccount.getId())).thenReturn(Optional.of(instagramAccount));
        configureInstagram();
        when(credentialService.metadataFor(instagramAccount))
                .thenReturn(new SocialCredentialMetadata(true, NOW.plusSeconds(3600), false, "scopes", NOW));
        Job job = publishJob(asset.getId(), instagramAccount.getId());
        when(jobService.createForWorkspace(any(), any(JobCreateRequest.class))).thenReturn(jobSummary(job));
        when(jobService.getJobEntityForWorkspace(workspace, job.getId())).thenReturn(Optional.of(job));

        PublicationSummary summary = service.createPublication(user, asset.getId(), new CreatePublicationRequest(instagramAccount.getId(), null));

        assertThat(summary.platform()).isEqualTo(SocialPlatform.INSTAGRAM);
    }

    @Test
    void driveInstagramPublicationKeepsPollingWhileInProgress() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        Publication publication = new Publication(workspace, asset, instagramAccount, null, owner, NOW.minusSeconds(5));
        Job job = publishJob(publication.getId(), asset.getId(), instagramAccount.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        publication.attachJob(job, NOW.minusSeconds(5));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));
        when(instagramPublishingService.drive(publication)).thenReturn(
                com.fdmultimedia.api.publishing.instagram.InstagramDriveOutcome.inProgress());

        PublicationDriveResponse response = service.driveInstagramPublication(workerPrincipal, job.getId(), "machine-1");

        assertThat(response.status()).isEqualTo("IN_PROGRESS");
        assertThat(publication.getStatus()).isEqualTo(PublicationStatus.PUBLISHING);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void driveInstagramPublicationCompletesJobAndPublicationOnPublished() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        Publication publication = new Publication(workspace, asset, instagramAccount, null, owner, NOW.minusSeconds(5));
        Job job = publishJob(publication.getId(), asset.getId(), instagramAccount.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        publication.attachJob(job, NOW.minusSeconds(5));
        publication.markPublishing(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));
        when(instagramPublishingService.drive(publication)).thenReturn(
                com.fdmultimedia.api.publishing.instagram.InstagramDriveOutcome.published("container-1", "media-1"));

        PublicationDriveResponse response = service.driveInstagramPublication(workerPrincipal, job.getId(), "machine-1");

        assertThat(response.status()).isEqualTo("PUBLISHED");
        assertThat(publication.getStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(publication.getProviderPublicationId()).isEqualTo("media-1");
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        ArgumentCaptor<PublishingAttempt> attemptCaptor = ArgumentCaptor.forClass(PublishingAttempt.class);
        verify(attempts).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getOutcome()).isEqualTo(PublishingAttemptOutcome.SUCCEEDED);
    }

    @Test
    void driveInstagramPublicationHandlesPublishedWithUnknownMediaIdWithoutError() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        Publication publication = new Publication(workspace, asset, instagramAccount, null, owner, NOW.minusSeconds(5));
        Job job = publishJob(publication.getId(), asset.getId(), instagramAccount.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        publication.attachJob(job, NOW.minusSeconds(5));
        publication.markPublishing(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));
        when(instagramPublishingService.drive(publication)).thenReturn(
                com.fdmultimedia.api.publishing.instagram.InstagramDriveOutcome.published("container-1", null));

        PublicationDriveResponse response = service.driveInstagramPublication(workerPrincipal, job.getId(), "machine-1");

        assertThat(response.status()).isEqualTo("PUBLISHED");
        assertThat(publication.getStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(publication.getProviderPublicationId()).isNull();
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void driveInstagramPublicationTerminalFailureMarksPublicationFailed() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        Publication publication = new Publication(workspace, asset, instagramAccount, null, owner, NOW.minusSeconds(5));
        Job job = publishJob(publication.getId(), asset.getId(), instagramAccount.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        publication.attachJob(job, NOW.minusSeconds(5));
        publication.markPublishing(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));
        when(instagramPublishingService.drive(publication)).thenReturn(
                com.fdmultimedia.api.publishing.instagram.InstagramDriveOutcome.failure("INSTAGRAM_AUTH_EXPIRED", "expired", true));

        PublicationDriveResponse response = service.driveInstagramPublication(workerPrincipal, job.getId(), "machine-1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(publication.getStatus()).isEqualTo(PublicationStatus.FAILED);
        assertThat(publication.getFailureCode()).isEqualTo("INSTAGRAM_AUTH_EXPIRED");
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void driveInstagramPublicationRetryableFailureRequeuesJobAndPublication() {
        MediaAsset asset = readyInspectedVideoAsset();
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-user-1", owner, NOW);
        Publication publication = new Publication(workspace, asset, instagramAccount, null, owner, NOW.minusSeconds(5));
        Job job = publishJob(publication.getId(), asset.getId(), instagramAccount.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        publication.attachJob(job, NOW.minusSeconds(5));
        publication.markPublishing(NOW.minusSeconds(1));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));
        when(instagramPublishingService.drive(publication)).thenReturn(
                com.fdmultimedia.api.publishing.instagram.InstagramDriveOutcome.failure("INSTAGRAM_RATE_LIMITED", "rate limited", false));

        PublicationDriveResponse response = service.driveInstagramPublication(workerPrincipal, job.getId(), "machine-1");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(publication.getStatus()).isEqualTo(PublicationStatus.PENDING);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void driveInstagramPublicationRejectsTestPlatformJobs() {
        MediaAsset asset = readyInspectedVideoAsset();
        Publication publication = new Publication(workspace, asset, account, null, owner, NOW.minusSeconds(5));
        Job job = publishJob(publication.getId(), asset.getId(), account.getId());
        job.claim(worker, NOW.minusSeconds(3), NOW.plusSeconds(30));
        job.start(worker, NOW.minusSeconds(2), NOW.plusSeconds(30));
        publication.attachJob(job, NOW.minusSeconds(5));
        when(jobService.requireJobForWorkerWorkspace(worker, job.getId())).thenReturn(job);
        when(publications.findByJobId(job.getId())).thenReturn(Optional.of(publication));

        assertThatThrownBy(() -> service.driveInstagramPublication(workerPrincipal, job.getId(), "machine-1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private void configureInstagram() {
        instagramProperties.setEnabled(true);
        instagramProperties.setAppId("app-id");
        instagramProperties.setAppSecret("app-secret");
        instagramProperties.setOauthRedirectUri("https://app.example.test/api/social-accounts/instagram/callback");
        instagramProperties.setPublicBaseUrl("https://app.example.test");
    }

    private MediaAsset readyInspectedVideoAsset() {
        return readyInspectedAsset(true, true);
    }

    private MediaAsset readyInspectedAsset(boolean hasVideo, boolean hasAudio) {
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

    private Job publishJob(UUID assetId, UUID socialAccountId) {
        return new Job(workspace, JobType.PUBLISH_MEDIA, publishPayload(assetId, socialAccountId), 3, NOW);
    }

    private Job publishJob(UUID publicationId, UUID assetId, UUID socialAccountId) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("publicationId", publicationId.toString());
        payload.put("assetId", assetId.toString());
        payload.put("socialAccountId", socialAccountId.toString());
        payload.put("provider", "INSTAGRAM");
        return new Job(workspace, JobType.PUBLISH_MEDIA, payload, 3, NOW);
    }

    private Map<String, Object> publishPayload(UUID assetId, UUID socialAccountId) {
        return Map.of(
                "publicationId", UUID.randomUUID().toString(),
                "assetId", assetId.toString(),
                "socialAccountId", socialAccountId.toString());
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
