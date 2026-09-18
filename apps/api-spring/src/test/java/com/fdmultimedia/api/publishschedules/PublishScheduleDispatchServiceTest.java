package com.fdmultimedia.api.publishschedules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountStatus;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.publishing.PublicationStatus;
import com.fdmultimedia.api.publishing.PublicationSummary;
import com.fdmultimedia.api.publishing.PublishingService;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Covers the atomic per-schedule dispatch logic in isolation. This is a
 * separate bean from {@link PublishScheduleDispatcher} specifically so its
 * {@code @Transactional dispatchOne()} is reached through a real Spring
 * proxy in production rather than a same-class self-invocation (which
 * silently skips {@code @Transactional} and caused a real
 * LazyInitializationException during runtime acceptance — see git history).
 */
class PublishScheduleDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final PublishScheduleRepository schedules = mock(PublishScheduleRepository.class);
    private final PublishingService publishingService = mock(PublishingService.class);
    private final PublishScheduleDispatchService service = new PublishScheduleDispatchService(
            schedules, publishingService, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private SocialAccount account;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        account = new SocialAccount(workspace, SocialPlatform.TEST, "My TEST Account", owner, NOW);
    }

    @Test
    void dispatchOneReturnsFalseWhenNothingDue() {
        when(schedules.findNextDueForUpdate(NOW)).thenReturn(Optional.empty());

        boolean result = service.dispatchOne();

        assertThat(result).isFalse();
        verify(publishingService, never()).createPublicationForSchedule(any(), any(), any(), any(), any(), any());
    }

    @Test
    void dispatchOneCreatesPublicationAndMarksDispatched() {
        PublishSchedule schedule = dueSchedule();
        when(schedules.findNextDueForUpdate(NOW)).thenReturn(Optional.of(schedule));
        UUID publicationId = UUID.randomUUID();
        when(publishingService.createPublicationForSchedule(
                eq(workspace), eq(schedule.getMediaAsset()), eq(account), eq("Caption"), eq(schedule.getContentDraft().getId()), eq(owner)))
                .thenReturn(publicationSummary(publicationId));

        boolean result = service.dispatchOne();

        assertThat(result).isTrue();
        assertThat(schedule.getStatus()).isEqualTo(PublishScheduleStatus.DISPATCHED);
        assertThat(schedule.getPublicationId()).isEqualTo(publicationId);
        assertThat(schedule.getDispatchedAt()).isEqualTo(NOW);
    }

    @Test
    void dispatchOneFailsWhenAccountNoLongerActiveWithoutCreatingPublication() {
        PublishSchedule schedule = dueSchedule();
        ReflectionTestUtils.setField(account, "status", SocialAccountStatus.DISCONNECTED);
        when(schedules.findNextDueForUpdate(NOW)).thenReturn(Optional.of(schedule));

        service.dispatchOne();

        assertThat(schedule.getStatus()).isEqualTo(PublishScheduleStatus.FAILED);
        assertThat(schedule.getFailureCode()).isEqualTo("SOCIAL_ACCOUNT_UNAVAILABLE");
        verify(publishingService, never()).createPublicationForSchedule(any(), any(), any(), any(), any(), any());
    }

    @Test
    void dispatchOneFailsWhenMediaNoLongerReadyWithoutCreatingPublication() {
        PublishSchedule schedule = dueSchedule();
        ReflectionTestUtils.setField(schedule.getMediaAsset(), "status", com.fdmultimedia.api.assets.MediaAssetStatus.FAILED);
        when(schedules.findNextDueForUpdate(NOW)).thenReturn(Optional.of(schedule));

        service.dispatchOne();

        assertThat(schedule.getStatus()).isEqualTo(PublishScheduleStatus.FAILED);
        assertThat(schedule.getFailureCode()).isEqualTo("MEDIA_UNAVAILABLE");
        verify(publishingService, never()).createPublicationForSchedule(any(), any(), any(), any(), any(), any());
    }

    @Test
    void dispatchOneFailsWhenPublishingServiceRejectsEligibility() {
        PublishSchedule schedule = dueSchedule();
        when(schedules.findNextDueForUpdate(NOW)).thenReturn(Optional.of(schedule));
        when(publishingService.createPublicationForSchedule(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Instagram integration is not configured"));

        service.dispatchOne();

        assertThat(schedule.getStatus()).isEqualTo(PublishScheduleStatus.FAILED);
        assertThat(schedule.getFailureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(schedule.getFailureMessage()).isEqualTo("Instagram integration is not configured");
    }

    @Test
    void dispatchOneMarksFailedOnUnexpectedExceptionWithoutPropagating() {
        PublishSchedule schedule = dueSchedule();
        when(schedules.findNextDueForUpdate(NOW)).thenReturn(Optional.of(schedule));
        when(publishingService.createPublicationForSchedule(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        boolean result = service.dispatchOne();

        assertThat(result).isTrue();
        assertThat(schedule.getStatus()).isEqualTo(PublishScheduleStatus.FAILED);
        assertThat(schedule.getFailureCode()).isEqualTo("DISPATCH_ERROR");
    }

    @Test
    void repeatedDispatchCallsForAnAlreadyDispatchedScheduleNeverDuplicatePublications() {
        // The claim query only ever returns SCHEDULED rows, so a schedule
        // already marked DISPATCHED is structurally unreachable again — this
        // asserts that invariant at the entity level: a second dispatch
        // attempt on the same in-memory instance is rejected outright.
        PublishSchedule schedule = dueSchedule();
        schedule.markDispatched(UUID.randomUUID(), NOW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> schedule.markDispatched(UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    private PublishSchedule dueSchedule() {
        MediaAsset asset = readyInspectedVideoAsset();
        ContentDraft draft = ContentDraft.fromExistingAsset(workspace, asset, "Title", "Caption", owner, NOW);
        return new PublishSchedule(workspace, draft, asset, account, "Caption", NOW.minus(Duration.ofMinutes(1)), owner, NOW.minus(Duration.ofHours(1)));
    }

    private PublicationSummary publicationSummary(UUID id) {
        return new PublicationSummary(
                id, UUID.randomUUID(), "media.mp4", UUID.randomUUID(), "TEST account",
                SocialPlatform.TEST, PublicationStatus.PENDING, UUID.randomUUID(), "caption", null, null,
                null, NOW, NOW, null, null, null, List.of());
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
