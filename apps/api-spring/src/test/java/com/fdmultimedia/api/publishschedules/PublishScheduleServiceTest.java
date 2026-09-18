package com.fdmultimedia.api.publishschedules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
import com.fdmultimedia.api.accounts.SocialAccountStatus;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.publishing.PublishingEligibilityService;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PublishScheduleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final PublishScheduleRepository schedules = mock(PublishScheduleRepository.class);
    private final ContentDraftRepository drafts = mock(ContentDraftRepository.class);
    private final SocialAccountRepository socialAccounts = mock(SocialAccountRepository.class);
    private final PublishingEligibilityService eligibilityService = new PublishingEligibilityService();
    private final PublishScheduleProperties properties = new PublishScheduleProperties();
    private final PublishScheduleService service = new PublishScheduleService(
            authService, schedules, drafts, socialAccounts, eligibilityService, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private SocialAccount account;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        account = new SocialAccount(workspace, SocialPlatform.TEST, "My TEST Account", owner, NOW);
        when(schedules.save(any(PublishSchedule.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsScheduleForReadyDraftWithSnapshottedFields() {
        ContentDraft draft = readyDraft("Original caption");
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        Instant scheduledFor = NOW.plus(Duration.ofHours(2));

        PublishScheduleSummary summary = service.create(user, draft.getId(), new CreatePublishScheduleRequest(account.getId(), scheduledFor));

        assertThat(summary.status()).isEqualTo(PublishScheduleStatus.SCHEDULED);
        assertThat(summary.contentDraftId()).isEqualTo(draft.getId());
        assertThat(summary.mediaAssetId()).isEqualTo(draft.getMediaAsset().getId());
        assertThat(summary.socialAccountId()).isEqualTo(account.getId());
        assertThat(summary.captionSnapshot()).isEqualTo("Original caption");
        assertThat(summary.scheduledFor()).isEqualTo(scheduledFor);
        assertThat(summary.publicationId()).isNull();
    }

    @Test
    void rejectsScheduleForDraftNotReady() {
        ContentDraft draft = draftInStatus(com.fdmultimedia.api.contentdrafts.ContentDraftStatus.DRAFT);
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.create(user, draft.getId(), new CreatePublishScheduleRequest(account.getId(), NOW.plus(Duration.ofHours(1)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsScheduleWhenDraftNotFoundInWorkspace() {
        UUID otherDraftId = UUID.randomUUID();
        when(drafts.findByWorkspaceAndId(workspace, otherDraftId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(user, otherDraftId, new CreatePublishScheduleRequest(account.getId(), NOW.plus(Duration.ofHours(1)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsScheduleForInactiveAccount() {
        ContentDraft draft = readyDraft(null);
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));
        SocialAccount disconnected = new SocialAccount(workspace, SocialPlatform.TEST, "Disconnected", owner, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(disconnected, "status", SocialAccountStatus.DISCONNECTED);
        when(socialAccounts.findByWorkspaceAndId(workspace, disconnected.getId())).thenReturn(Optional.of(disconnected));

        assertThatThrownBy(() -> service.create(user, draft.getId(), new CreatePublishScheduleRequest(disconnected.getId(), NOW.plus(Duration.ofHours(1)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsScheduleTooNearFuture() {
        ContentDraft draft = readyDraft(null);
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.create(user, draft.getId(), new CreatePublishScheduleRequest(account.getId(), NOW.plusSeconds(5))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsScheduleBeyondMaxHorizon() {
        ContentDraft draft = readyDraft(null);
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.create(user, draft.getId(), new CreatePublishScheduleRequest(account.getId(), NOW.plus(Duration.ofDays(400)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsIneligibleMediaForPlatformAtCreationTime() {
        ContentDraft draft = readyDraft(null);
        MediaAsset shortAsset = draft.getMediaAsset();
        org.springframework.test.util.ReflectionTestUtils.setField(shortAsset, "durationMs", 500L);
        when(drafts.findByWorkspaceAndId(workspace, draft.getId())).thenReturn(Optional.of(draft));
        SocialAccount instagramAccount = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-1", owner, NOW);
        when(socialAccounts.findByWorkspaceAndId(workspace, instagramAccount.getId())).thenReturn(Optional.of(instagramAccount));

        assertThatThrownBy(() -> service.create(user, draft.getId(), new CreatePublishScheduleRequest(instagramAccount.getId(), NOW.plus(Duration.ofHours(1)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancelsScheduledItem() {
        PublishSchedule schedule = scheduledItem();
        when(schedules.findByWorkspaceAndIdForUpdate(workspace, schedule.getId())).thenReturn(Optional.of(schedule));

        PublishScheduleSummary summary = service.cancel(user, schedule.getId());

        assertThat(summary.status()).isEqualTo(PublishScheduleStatus.CANCELLED);
    }

    @Test
    void rejectsCancellingAlreadyDispatchedItem() {
        PublishSchedule schedule = scheduledItem();
        schedule.markDispatched(UUID.randomUUID(), NOW);
        when(schedules.findByWorkspaceAndIdForUpdate(workspace, schedule.getId())).thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.cancel(user, schedule.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reschedulesScheduledItemToNewValidTime() {
        PublishSchedule schedule = scheduledItem();
        when(schedules.findByWorkspaceAndIdForUpdate(workspace, schedule.getId())).thenReturn(Optional.of(schedule));
        Instant newTime = NOW.plus(Duration.ofDays(3));

        PublishScheduleSummary summary = service.reschedule(user, schedule.getId(), new RescheduleRequest(newTime));

        assertThat(summary.scheduledFor()).isEqualTo(newTime);
        assertThat(summary.status()).isEqualTo(PublishScheduleStatus.SCHEDULED);
    }

    @Test
    void rejectsReschedulingToTooNearFuture() {
        PublishSchedule schedule = scheduledItem();
        when(schedules.findByWorkspaceAndIdForUpdate(workspace, schedule.getId())).thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.reschedule(user, schedule.getId(), new RescheduleRequest(NOW.plusSeconds(1))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsReschedulingNonScheduledItem() {
        PublishSchedule schedule = scheduledItem();
        schedule.cancel(NOW);
        when(schedules.findByWorkspaceAndIdForUpdate(workspace, schedule.getId())).thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.reschedule(user, schedule.getId(), new RescheduleRequest(NOW.plus(Duration.ofHours(2)))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getForRejectsScheduleFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(schedules.findByWorkspaceAndId(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(user, otherId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void calendarRejectsIntervalWithoutToAfterFrom() {
        assertThatThrownBy(() -> service.calendar(user, NOW, NOW))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private PublishSchedule scheduledItem() {
        ContentDraft draft = readyDraft("Caption");
        return new PublishSchedule(
                workspace, draft, draft.getMediaAsset(), account, "Caption", NOW.plus(Duration.ofHours(2)), owner, NOW);
    }

    private ContentDraft readyDraft(String caption) {
        MediaAsset asset = readyInspectedVideoAsset();
        return ContentDraft.fromExistingAsset(workspace, asset, "Title", caption, owner, NOW);
    }

    private ContentDraft draftInStatus(com.fdmultimedia.api.contentdrafts.ContentDraftStatus status) {
        if (status == com.fdmultimedia.api.contentdrafts.ContentDraftStatus.DRAFT) {
            MediaAsset source = readyInspectedVideoAsset();
            com.fdmultimedia.api.highlights.HighlightAnalysis analysis = new com.fdmultimedia.api.highlights.HighlightAnalysis(
                    workspace, source, new Job(workspace, JobType.ANALYZE_HIGHLIGHTS, Map.of(), 3, NOW), "DETERMINISTIC_V1", "v1", NOW);
            com.fdmultimedia.api.highlights.HighlightCandidate candidate = new com.fdmultimedia.api.highlights.HighlightCandidate(
                    analysis, 0, 3000, new BigDecimal("0.9"), "reason", 1, NOW);
            MediaAsset clip = MediaAsset.clipDerivative(workspace, owner, source, NOW);
            clip.markProcessing(NOW);
            Job clipJob = new Job(workspace, JobType.CREATE_CLIP, Map.of(), 3, NOW);
            return ContentDraft.fromHighlightCandidate(workspace, candidate, clip, clipJob, owner, NOW);
        }
        return readyDraft(null);
    }

    private MediaAsset readyInspectedVideoAsset() {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        asset.markImporting(NOW.minusSeconds(5));
        asset.markReady(new MediaImportMetadata("media.mp4", "video/mp4", 12_000, "0".repeat(64), null, null, null, null, null, "mp4"), "media-assets", "storage-key", NOW.minusSeconds(4));
        Job inspectionJob = new Job(workspace, JobType.INSPECT_MEDIA, Map.of("assetId", asset.getId().toString()), 3, NOW.minusSeconds(3));
        asset.attachInspectionJob(inspectionJob, NOW.minusSeconds(2));
        asset.markInspecting(NOW.minusSeconds(1));
        asset.markInspected(new MediaInspectionMetadata(
                12_000L, 1920, 1080, "h264", "aac", "mp4", new BigDecimal("29.970"), 800_000L, true, true), NOW);
        return asset;
    }
}
