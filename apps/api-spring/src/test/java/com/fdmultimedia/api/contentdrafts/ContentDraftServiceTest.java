package com.fdmultimedia.api.contentdrafts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.CreateClipRequest;
import com.fdmultimedia.api.assets.CreateClipResponse;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaAssetService;
import com.fdmultimedia.api.assets.MediaAssetSummary;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.highlights.HighlightAnalysis;
import com.fdmultimedia.api.highlights.HighlightAnalysisStatus;
import com.fdmultimedia.api.highlights.HighlightCandidate;
import com.fdmultimedia.api.highlights.HighlightCandidateRepository;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobService;
import com.fdmultimedia.api.jobs.JobSummary;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.publishing.Publication;
import com.fdmultimedia.api.publishing.PublicationRepository;
import com.fdmultimedia.api.publishing.PublicationStatus;
import com.fdmultimedia.api.publishing.PublicationSummary;
import com.fdmultimedia.api.publishing.PublishingService;
import com.fdmultimedia.api.users.AppUser;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ContentDraftServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final ContentDraftRepository drafts = mock(ContentDraftRepository.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final MediaAssetService mediaAssetService = mock(MediaAssetService.class);
    private final HighlightCandidateRepository candidates = mock(HighlightCandidateRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final PublicationRepository publications = mock(PublicationRepository.class);
    private final PublishingService publishingService = mock(PublishingService.class);
    private final ContentDraftService service = new ContentDraftService(
            authService, drafts, assets, mediaAssetService, candidates, jobService, publications, publishingService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        when(drafts.save(any(ContentDraft.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(publications.findByWorkspaceAndContentDraftIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(publishingService.listForContentDraft(any(), any())).thenReturn(List.of());
    }

    // ---- creation: existing asset ----

    @Test
    void createsDraftDirectlyReadyForEligibleExistingAsset() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        ContentDraftSummary summary = service.createFromAsset(user, new CreateContentDraftRequest(asset.getId(), "Title", "Caption"));

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.READY);
        assertThat(summary.workflowStage()).isEqualTo(ContentDraftWorkflowStage.READY);
        assertThat(summary.sourceAssetId()).isEqualTo(asset.getId());
        assertThat(summary.mediaAssetId()).isEqualTo(asset.getId());
        assertThat(summary.title()).isEqualTo("Title");
        assertThat(summary.caption()).isEqualTo("Caption");
    }

    @Test
    void rejectsDraftFromNotReadyAsset() {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.createFromAsset(user, new CreateContentDraftRequest(asset.getId(), null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsDraftFromAssetInAnotherWorkspace() {
        UUID otherAssetId = UUID.randomUUID();
        when(assets.findByWorkspaceAndId(workspace, otherAssetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createFromAsset(user, new CreateContentDraftRequest(otherAssetId, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsCaptionOverMaxLength() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.createFromAsset(
                user, new CreateContentDraftRequest(asset.getId(), null, "a".repeat(2201))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsTitleOverMaxLength() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.createFromAsset(
                user, new CreateContentDraftRequest(asset.getId(), "a".repeat(201), null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- creation: highlight candidate ----

    @Test
    void createsDraftFromHighlightCandidateInClipPendingStage() {
        MediaAsset source = readyInspectedVideoAsset();
        HighlightCandidate candidate = highlightCandidate(source, 1_000, 6_000);
        when(candidates.findByWorkspaceAndId(workspace, candidate.getId())).thenReturn(Optional.of(candidate));
        MediaAsset clipInProgress = pendingDerivative(source, com.fdmultimedia.api.assets.MediaDerivationType.CLIP);
        Job clipJob = new Job(workspace, JobType.CREATE_CLIP, Map.of(), 3, NOW);
        when(mediaAssetService.createClip(eq(user), eq(source.getId()), any(CreateClipRequest.class)))
                .thenReturn(new CreateClipResponse(minimalSummary(clipInProgress.getId()), minimalJobSummary(clipJob.getId())));
        when(assets.findByWorkspaceAndId(workspace, clipInProgress.getId())).thenReturn(Optional.of(clipInProgress));
        when(jobService.getJobEntityForWorkspace(workspace, clipJob.getId())).thenReturn(Optional.of(clipJob));

        ContentDraftSummary summary = service.createFromHighlightCandidate(user, candidate.getId());

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.DRAFT);
        assertThat(summary.workflowStage()).isEqualTo(ContentDraftWorkflowStage.CLIP_PENDING);
        assertThat(summary.sourceAssetId()).isEqualTo(source.getId());
        assertThat(summary.mediaAssetId()).isEqualTo(clipInProgress.getId());
        assertThat(summary.sourceHighlightCandidateId()).isEqualTo(candidate.getId());
    }

    @Test
    void rejectsDraftFromCandidateWithIncompleteAnalysis() {
        MediaAsset source = readyInspectedVideoAsset();
        HighlightAnalysis analysis = new HighlightAnalysis(workspace, source, new Job(workspace, JobType.ANALYZE_HIGHLIGHTS, Map.of(), 3, NOW), "DETERMINISTIC_V1", "v1", NOW);
        HighlightCandidate candidate = new HighlightCandidate(analysis, 1_000, 6_000, new BigDecimal("0.9"), "reason", 1, NOW);
        when(candidates.findByWorkspaceAndId(workspace, candidate.getId())).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.createFromHighlightCandidate(user, candidate.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- workflow reconciliation ----

    @Test
    void reconciliationLeavesDraftWaitingWhileClipStillProcessing() {
        ContentDraft draft = draftInClipPending();
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));

        ContentDraftSummary summary = service.getFor(user, draft.getId());

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.DRAFT);
        assertThat(summary.workflowStage()).isEqualTo(ContentDraftWorkflowStage.CLIP_PENDING);
        verify(mediaAssetService, never()).createSocialVertical(any(), any());
    }

    @Test
    void reconciliationAdvancesClipPendingToVerticalPendingWhenClipReady() {
        ContentDraft draft = draftInClipPending();
        MediaAsset clip = draft.getMediaAsset();
        markReadyInspected(clip);
        MediaAsset verticalInProgress = pendingDerivative(clip, com.fdmultimedia.api.assets.MediaDerivationType.SOCIAL_VERTICAL);
        Job verticalJob = new Job(workspace, JobType.CREATE_SOCIAL_VERTICAL, Map.of(), 3, NOW);
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));
        when(mediaAssetService.createSocialVertical(user, clip.getId()))
                .thenReturn(new CreateClipResponse(minimalSummary(verticalInProgress.getId()), minimalJobSummary(verticalJob.getId())));
        when(assets.findByWorkspaceAndId(workspace, verticalInProgress.getId())).thenReturn(Optional.of(verticalInProgress));
        when(jobService.getJobEntityForWorkspace(workspace, verticalJob.getId())).thenReturn(Optional.of(verticalJob));

        ContentDraftSummary summary = service.getFor(user, draft.getId());

        assertThat(summary.workflowStage()).isEqualTo(ContentDraftWorkflowStage.VERTICAL_PENDING);
        assertThat(summary.mediaAssetId()).isEqualTo(verticalInProgress.getId());
        verify(mediaAssetService, times(1)).createSocialVertical(user, clip.getId());
    }

    @Test
    void reconciliationIsIdempotentAcrossRepeatedReads() {
        ContentDraft draft = draftInClipPending();
        MediaAsset clip = draft.getMediaAsset();
        markReadyInspected(clip);
        MediaAsset verticalInProgress = pendingDerivative(clip, com.fdmultimedia.api.assets.MediaDerivationType.SOCIAL_VERTICAL);
        Job verticalJob = new Job(workspace, JobType.CREATE_SOCIAL_VERTICAL, Map.of(), 3, NOW);
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));
        when(mediaAssetService.createSocialVertical(user, clip.getId()))
                .thenReturn(new CreateClipResponse(minimalSummary(verticalInProgress.getId()), minimalJobSummary(verticalJob.getId())));
        when(assets.findByWorkspaceAndId(workspace, verticalInProgress.getId())).thenReturn(Optional.of(verticalInProgress));
        when(jobService.getJobEntityForWorkspace(workspace, verticalJob.getId())).thenReturn(Optional.of(verticalJob));

        service.getFor(user, draft.getId());
        service.getFor(user, draft.getId());
        service.getFor(user, draft.getId());

        verify(mediaAssetService, times(1)).createSocialVertical(any(), any());
    }

    @Test
    void reconciliationAdvancesVerticalPendingToReadyWhenVerticalReady() {
        ContentDraft draft = draftInClipPending();
        MediaAsset clip = draft.getMediaAsset();
        markReadyInspected(clip);
        MediaAsset vertical = pendingDerivative(clip, com.fdmultimedia.api.assets.MediaDerivationType.SOCIAL_VERTICAL);
        markReadyInspected(vertical);
        draft.advanceToVerticalPending(vertical, new Job(workspace, JobType.CREATE_SOCIAL_VERTICAL, Map.of(), 3, NOW), NOW);
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));

        ContentDraftSummary summary = service.getFor(user, draft.getId());

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.READY);
        assertThat(summary.workflowStage()).isEqualTo(ContentDraftWorkflowStage.READY);
    }

    @Test
    void reconciliationMarksDraftFailedWhenClipDerivativeFails() {
        ContentDraft draft = draftInClipPending();
        MediaAsset clip = draft.getMediaAsset();
        clip.markFailed("FFMPEG_ERROR", "encode failed", NOW);
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));

        ContentDraftSummary summary = service.getFor(user, draft.getId());

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.FAILED);
        assertThat(summary.failureCode()).isEqualTo("FFMPEG_ERROR");
    }

    // ---- publishing ----

    @Test
    void publishCreatesPublicationThroughPublishingServiceAndMarksPublishing() {
        ContentDraft draft = draftReadyFromExistingAsset();
        UUID accountId = UUID.randomUUID();
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));
        PublicationSummary publicationSummary = publicationSummary(draft.getId());
        when(publishingService.createPublicationForDraft(user, draft.getMediaAsset(), accountId, draft.getCaption(), draft.getId()))
                .thenReturn(publicationSummary);

        ContentDraftSummary summary = service.publish(user, draft.getId(), new PublishContentDraftRequest(accountId));

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.PUBLISHING);
        verify(publishingService).createPublicationForDraft(user, draft.getMediaAsset(), accountId, draft.getCaption(), draft.getId());
    }

    @Test
    void rejectsPublishWhenDraftNotReady() {
        ContentDraft draft = draftInClipPending();
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.publish(user, draft.getId(), new PublishContentDraftRequest(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(publishingService);
    }

    @Test
    void captionEditAfterUpdateNeverTouchesPublishing() {
        ContentDraft draft = draftReadyFromExistingAsset();
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));

        service.update(user, draft.getId(), new UpdateContentDraftRequest("New title", "New caption"));

        assertThat(draft.getCaption()).isEqualTo("New caption");
        verify(publishingService, never()).createPublicationForDraft(any(), any(), any(), any(), any());
        verify(publications, never()).save(any());
    }

    @Test
    void failedPublicationRevertsDraftToReadyRatherThanDestroyingIt() {
        ContentDraft draft = draftReadyFromExistingAsset();
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));
        draft.applyPublishingDisplayState(ContentDraftStatus.PUBLISHING, null, NOW);
        Publication failed = new Publication(workspace, draft.getMediaAsset(), testAccount(), null, owner, draft.getId(), NOW);
        failed.markPublishing(NOW);
        failed.markFailed("PROVIDER_ERROR", "failed", NOW);
        when(publications.findByWorkspaceAndContentDraftIdOrderByCreatedAtDesc(workspace, draft.getId())).thenReturn(List.of(failed));

        ContentDraftSummary summary = service.getFor(user, draft.getId());

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.READY);
    }

    @Test
    void successfulPublicationMarksDraftPublished() {
        ContentDraft draft = draftReadyFromExistingAsset();
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));
        draft.applyPublishingDisplayState(ContentDraftStatus.PUBLISHING, null, NOW);
        Publication published = new Publication(workspace, draft.getMediaAsset(), testAccount(), null, owner, draft.getId(), NOW);
        published.markPublishing(NOW);
        published.markPublished("req-1", "pub-1", NOW.plusSeconds(5), NOW.plusSeconds(5));
        when(publications.findByWorkspaceAndContentDraftIdOrderByCreatedAtDesc(workspace, draft.getId())).thenReturn(List.of(published));

        ContentDraftSummary summary = service.getFor(user, draft.getId());

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.PUBLISHED);
        assertThat(summary.publishedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void publishAllowedAgainFromAlreadyPublishedDraft() {
        ContentDraft draft = draftReadyFromExistingAsset();
        draft.applyPublishingDisplayState(ContentDraftStatus.PUBLISHED, NOW, NOW);
        UUID accountId = UUID.randomUUID();
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));
        when(publications.findByWorkspaceAndContentDraftIdOrderByCreatedAtDesc(workspace, draft.getId())).thenReturn(List.of());
        when(publishingService.createPublicationForDraft(eq(user), eq(draft.getMediaAsset()), eq(accountId), any(), eq(draft.getId())))
                .thenReturn(publicationSummary(draft.getId()));

        ContentDraftSummary summary = service.publish(user, draft.getId(), new PublishContentDraftRequest(accountId));

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.PUBLISHING);
    }

    // ---- workspace isolation ----

    @Test
    void getForRejectsDraftFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(user, otherId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateRejectsDraftFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(drafts.findByWorkspaceAndId(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(user, otherId, new UpdateContentDraftRequest("x", "y")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- retry preparation ----

    @Test
    void retryPreparationRejectsWhenDraftNotFailed() {
        ContentDraft draft = draftInClipPending();
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.retryPreparation(user, draft.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void retryPreparationRecreatesClipJobFromCandidateTimingWhenClipFailed() {
        ContentDraft draft = draftInClipPending();
        HighlightCandidate candidate = draft.getSourceHighlightCandidate();
        MediaAsset failedClip = draft.getMediaAsset();
        failedClip.markFailed("FFMPEG_ERROR", "boom", NOW);
        draft.markPreparationFailed("FFMPEG_ERROR", "boom", NOW);
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));
        MediaAsset newClip = pendingDerivative(draft.getSourceAsset(), com.fdmultimedia.api.assets.MediaDerivationType.CLIP);
        Job newClipJob = new Job(workspace, JobType.CREATE_CLIP, Map.of(), 3, NOW);
        when(mediaAssetService.createClip(eq(user), eq(draft.getSourceAsset().getId()), any(CreateClipRequest.class)))
                .thenReturn(new CreateClipResponse(minimalSummary(newClip.getId()), minimalJobSummary(newClipJob.getId())));
        when(assets.findByWorkspaceAndId(workspace, newClip.getId())).thenReturn(Optional.of(newClip));
        when(jobService.getJobEntityForWorkspace(workspace, newClipJob.getId())).thenReturn(Optional.of(newClipJob));

        ContentDraftSummary summary = service.retryPreparation(user, draft.getId());

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.DRAFT);
        assertThat(summary.workflowStage()).isEqualTo(ContentDraftWorkflowStage.CLIP_PENDING);
        assertThat(summary.mediaAssetId()).isEqualTo(newClip.getId());
        assertThat(candidate).isNotNull();
    }

    @Test
    void retryPreparationRecreatesVerticalFromClipParentWhenVerticalFailed() {
        ContentDraft draft = draftInClipPending();
        MediaAsset clip = draft.getMediaAsset();
        markReadyInspected(clip);
        MediaAsset vertical = pendingDerivative(clip, com.fdmultimedia.api.assets.MediaDerivationType.SOCIAL_VERTICAL);
        draft.advanceToVerticalPending(vertical, new Job(workspace, JobType.CREATE_SOCIAL_VERTICAL, Map.of(), 3, NOW), NOW);
        vertical.markFailed("FFMPEG_ERROR", "boom", NOW);
        draft.markPreparationFailed("FFMPEG_ERROR", "boom", NOW);
        when(drafts.findByWorkspaceAndIdForUpdate(workspace, draft.getId())).thenReturn(Optional.of(draft));
        MediaAsset newVertical = pendingDerivative(clip, com.fdmultimedia.api.assets.MediaDerivationType.SOCIAL_VERTICAL);
        Job newVerticalJob = new Job(workspace, JobType.CREATE_SOCIAL_VERTICAL, Map.of(), 3, NOW);
        when(mediaAssetService.createSocialVertical(user, clip.getId()))
                .thenReturn(new CreateClipResponse(minimalSummary(newVertical.getId()), minimalJobSummary(newVerticalJob.getId())));
        when(assets.findByWorkspaceAndId(workspace, newVertical.getId())).thenReturn(Optional.of(newVertical));
        when(jobService.getJobEntityForWorkspace(workspace, newVerticalJob.getId())).thenReturn(Optional.of(newVerticalJob));

        ContentDraftSummary summary = service.retryPreparation(user, draft.getId());

        assertThat(summary.status()).isEqualTo(ContentDraftStatus.DRAFT);
        assertThat(summary.workflowStage()).isEqualTo(ContentDraftWorkflowStage.VERTICAL_PENDING);
        assertThat(summary.mediaAssetId()).isEqualTo(newVertical.getId());
    }

    // ---- helpers ----

    private ContentDraft draftInClipPending() {
        MediaAsset source = readyInspectedVideoAsset();
        HighlightCandidate candidate = highlightCandidate(source, 1_000, 6_000);
        MediaAsset clipInProgress = pendingDerivative(source, com.fdmultimedia.api.assets.MediaDerivationType.CLIP);
        Job clipJob = new Job(workspace, JobType.CREATE_CLIP, Map.of(), 3, NOW);
        return ContentDraft.fromHighlightCandidate(workspace, candidate, clipInProgress, clipJob, owner, NOW);
    }

    private ContentDraft draftReadyFromExistingAsset() {
        MediaAsset asset = readyInspectedVideoAsset();
        return ContentDraft.fromExistingAsset(workspace, asset, "Title", "Original caption", owner, NOW);
    }

    private SocialAccount testAccount() {
        return new SocialAccount(workspace, SocialPlatform.TEST, "TEST account", owner, NOW);
    }

    private PublicationSummary publicationSummary(UUID draftId) {
        return new PublicationSummary(
                UUID.randomUUID(), UUID.randomUUID(), "media.mp4", UUID.randomUUID(), "TEST account",
                SocialPlatform.TEST, PublicationStatus.PENDING, UUID.randomUUID(), "caption", null, null,
                draftId, NOW, NOW, null, null, null, List.of());
    }

    private HighlightCandidate highlightCandidate(MediaAsset source, long startMs, long endMs) {
        Job analysisJob = new Job(workspace, JobType.ANALYZE_HIGHLIGHTS, Map.of(), 3, NOW);
        HighlightAnalysis analysis = new HighlightAnalysis(workspace, source, analysisJob, "DETERMINISTIC_V1", "v1", NOW);
        analysis.markRunning(NOW);
        analysis.markSucceeded(NOW);
        return new HighlightCandidate(analysis, startMs, endMs, new BigDecimal("0.9"), "reason", 1, NOW);
    }

    private MediaAsset readyInspectedVideoAsset() {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        markReadyInspected(asset);
        return asset;
    }

    private MediaAsset pendingDerivative(MediaAsset parent, com.fdmultimedia.api.assets.MediaDerivationType type) {
        MediaAsset derivative = type == com.fdmultimedia.api.assets.MediaDerivationType.CLIP
                ? MediaAsset.clipDerivative(workspace, owner, parent, NOW)
                : MediaAsset.socialVerticalDerivative(workspace, owner, parent, NOW);
        derivative.markProcessing(NOW);
        return derivative;
    }

    private void markReadyInspected(MediaAsset asset) {
        if (asset.getStatus() == com.fdmultimedia.api.assets.MediaAssetStatus.PENDING) {
            asset.markImporting(NOW.minusSeconds(5));
            asset.markReady(new MediaImportMetadata("media.mp4", "video/mp4", 12_000, "0".repeat(64), null, null, null, null, null, "mp4"), "media-assets", "storage-key", NOW.minusSeconds(4));
        } else if (asset.getStatus() == com.fdmultimedia.api.assets.MediaAssetStatus.PROCESSING) {
            asset.markReady(new MediaImportMetadata("clip.mp4", "video/mp4", 6_000, "1".repeat(64), null, null, null, null, null, "mp4"), "media-assets", "storage-key-" + asset.getId(), NOW.minusSeconds(1));
        }
        Job inspectionJob = new Job(workspace, JobType.INSPECT_MEDIA, Map.of("assetId", asset.getId().toString()), 3, NOW.minusSeconds(3));
        asset.attachInspectionJob(inspectionJob, NOW.minusSeconds(2));
        asset.markInspecting(NOW.minusSeconds(1));
        asset.markInspected(new MediaInspectionMetadata(
                12_000L, 1920, 1080, "h264", "aac", "mp4", new BigDecimal("29.970"), 800_000L, true, true), NOW);
    }

    private MediaAssetSummary minimalSummary(UUID id) {
        return new MediaAssetSummary(
                id,     // id
                null,   // sourceType
                null,   // sourceUrl
                null,   // parentAssetId
                null,   // derivationType
                com.fdmultimedia.api.assets.MediaAssetStatus.PENDING, // status
                null,   // originalFilename
                null,   // contentType
                null,   // fileSizeBytes
                null,   // checksumSha256
                null,   // durationMs
                null,   // width
                null,   // height
                null,   // videoCodec
                null,   // audioCodec
                null,   // containerFormat
                null,   // importJobId
                null,   // processingJobId
                null,   // inspectionStatus
                null,   // inspectionJobId
                null,   // inspectionErrorCode
                null,   // inspectionErrorMessage
                null,   // frameRate
                null,   // bitrate
                null,   // hasVideo
                null,   // hasAudio
                null,   // errorCode
                null,   // errorMessage
                NOW,    // createdAt
                NOW,    // updatedAt
                null);  // readyAt
    }

    private JobSummary minimalJobSummary(UUID id) {
        return new JobSummary(id, JobType.CREATE_CLIP, com.fdmultimedia.api.jobs.JobStatus.QUEUED, Map.of(), null, null, null,
                null, null, 0, 3, NOW, null, null, null, null, NOW, NOW);
    }
}
