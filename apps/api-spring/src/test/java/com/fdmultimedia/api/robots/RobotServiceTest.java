package com.fdmultimedia.api.robots;

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
import com.fdmultimedia.api.assets.MediaAssetRepository;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsources.ContentSource;
import com.fdmultimedia.api.contentsources.ContentSourceRepository;
import com.fdmultimedia.api.jobs.Job;
import com.fdmultimedia.api.jobs.JobType;
import com.fdmultimedia.api.publishschedules.PublishScheduleProperties;
import com.fdmultimedia.api.users.AppUser;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RobotServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final SocialAccountRepository socialAccounts = mock(SocialAccountRepository.class);
    private final ContentSourceRepository contentSources = mock(ContentSourceRepository.class);
    private final RobotProperties properties = new RobotProperties();
    private final PublishScheduleProperties publishScheduleProperties = new PublishScheduleProperties();
    private final RobotAutomationDispatchService dispatchService = mock(RobotAutomationDispatchService.class);
    private final RobotRunOrchestrator orchestrator = mock(RobotRunOrchestrator.class);
    private final RobotService service = new RobotService(
            authService, robots, assets, socialAccounts, contentSources, properties, publishScheduleProperties,
            dispatchService, orchestrator, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private SocialAccount testAccount;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        testAccount = new SocialAccount(workspace, SocialPlatform.TEST, "TEST account", owner, NOW);
        when(robots.save(any(Robot.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsDraftOnlyRobotWithoutAccountOrDelay() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        RobotSummary summary = service.create(user, new CreateRobotRequest(
                "Romanian Tech Clips", "desc", RobotAutonomyMode.DRAFT_ONLY, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, null,
                RobotCadenceType.MANUAL_ONLY, null, null, null));

        assertThat(summary.status()).isEqualTo(RobotStatus.ACTIVE);
        assertThat(summary.autonomyMode()).isEqualTo(RobotAutonomyMode.DRAFT_ONLY);
        assertThat(summary.targetSocialAccountId()).isNull();
        assertThat(summary.scheduleDelayMinutes()).isNull();
        assertThat(summary.cadenceType()).isEqualTo(RobotCadenceType.MANUAL_ONLY);
    }

    @Test
    void createsAutoScheduleRobotWithTestAccount() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, testAccount.getId())).thenReturn(Optional.of(testAccount));

        RobotSummary summary = service.create(user, new CreateRobotRequest(
                "Auto Robot", null, RobotAutonomyMode.AUTO_SCHEDULE, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, testAccount.getId(),
                RobotCadenceType.INTERVAL, 6, 60, 2));

        assertThat(summary.autonomyMode()).isEqualTo(RobotAutonomyMode.AUTO_SCHEDULE);
        assertThat(summary.targetSocialAccountId()).isEqualTo(testAccount.getId());
        assertThat(summary.scheduleDelayMinutes()).isEqualTo(60);
        assertThat(summary.nextRunAt()).isEqualTo(NOW.plusSeconds(6 * 3600L));
    }

    @Test
    void rejectsAutoScheduleWithInstagramAccount() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        SocialAccount instagram = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-1", owner, NOW);
        when(socialAccounts.findByWorkspaceAndId(workspace, instagram.getId())).thenReturn(Optional.of(instagram));

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Bad Robot", null, RobotAutonomyMode.AUTO_SCHEDULE, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, instagram.getId(),
                RobotCadenceType.MANUAL_ONLY, null, 60, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void allowsReviewRequiredWithInstagramAccount() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        SocialAccount instagram = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-1", owner, NOW);
        when(socialAccounts.findByWorkspaceAndId(workspace, instagram.getId())).thenReturn(Optional.of(instagram));

        RobotSummary summary = service.create(user, new CreateRobotRequest(
                "Review Robot", null, RobotAutonomyMode.REVIEW_REQUIRED, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, instagram.getId(),
                RobotCadenceType.MANUAL_ONLY, null, 120, null));

        assertThat(summary.targetSocialAccountId()).isEqualTo(instagram.getId());
    }

    @Test
    void rejectsSourceAssetNotReady() {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Robot", null, RobotAutonomyMode.DRAFT_ONLY, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, null,
                RobotCadenceType.MANUAL_ONLY, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsSourceAssetFromAnotherWorkspace() {
        UUID otherAssetId = UUID.randomUUID();
        when(assets.findByWorkspaceAndId(workspace, otherAssetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Robot", null, RobotAutonomyMode.DRAFT_ONLY, RobotSourcePolicy.EXISTING_ASSET, otherAssetId, null, null, null,
                RobotCadenceType.MANUAL_ONLY, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsIntervalCadenceWithoutHours() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Robot", null, RobotAutonomyMode.DRAFT_ONLY, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, null,
                RobotCadenceType.INTERVAL, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsCadenceIntervalOutsideBounds() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Robot", null, RobotAutonomyMode.DRAFT_ONLY, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, null,
                RobotCadenceType.INTERVAL, 200, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAutoScheduleDelayBelowPublishScheduleMinLead() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));
        when(socialAccounts.findByWorkspaceAndId(workspace, testAccount.getId())).thenReturn(Optional.of(testAccount));

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Robot", null, RobotAutonomyMode.AUTO_SCHEDULE, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, testAccount.getId(),
                RobotCadenceType.MANUAL_ONLY, null, 0, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsMissingTargetAccountForReviewRequired() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Robot", null, RobotAutonomyMode.REVIEW_REQUIRED, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, null,
                RobotCadenceType.MANUAL_ONLY, null, 60, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsMaxRunsPerDayOutsideBounds() {
        MediaAsset asset = readyInspectedVideoAsset();
        when(assets.findByWorkspaceAndId(workspace, asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Robot", null, RobotAutonomyMode.DRAFT_ONLY, RobotSourcePolicy.EXISTING_ASSET, asset.getId(), null, null, null,
                RobotCadenceType.MANUAL_ONLY, null, null, 100)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createsContentSourceRobot() {
        ContentSource source = new ContentSource(workspace, "Incoming Tech Videos", null, owner, NOW);
        when(contentSources.findByWorkspaceAndId(workspace, source.getId())).thenReturn(Optional.of(source));

        RobotSummary summary = service.create(user, new CreateRobotRequest(
                "Dynamic Robot", null, RobotAutonomyMode.DRAFT_ONLY, RobotSourcePolicy.CONTENT_SOURCE, null,
                source.getId(), RobotSelectionPolicy.NEWEST_UNPROCESSED, null,
                RobotCadenceType.MANUAL_ONLY, null, null, null));

        assertThat(summary.sourcePolicy()).isEqualTo(RobotSourcePolicy.CONTENT_SOURCE);
        assertThat(summary.contentSourceId()).isEqualTo(source.getId());
        assertThat(summary.contentSourceName()).isEqualTo("Incoming Tech Videos");
        assertThat(summary.selectionPolicy()).isEqualTo(RobotSelectionPolicy.NEWEST_UNPROCESSED);
        assertThat(summary.sourceAssetId()).isNull();
    }

    @Test
    void rejectsContentSourceRobotWithoutSelectionPolicy() {
        ContentSource source = new ContentSource(workspace, "Incoming Tech Videos", null, owner, NOW);
        when(contentSources.findByWorkspaceAndId(workspace, source.getId())).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Dynamic Robot", null, RobotAutonomyMode.DRAFT_ONLY, RobotSourcePolicy.CONTENT_SOURCE, null,
                source.getId(), null, null,
                RobotCadenceType.MANUAL_ONLY, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsContentSourceRobotFromAnotherWorkspace() {
        UUID otherSourceId = UUID.randomUUID();
        when(contentSources.findByWorkspaceAndId(workspace, otherSourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(user, new CreateRobotRequest(
                "Dynamic Robot", null, RobotAutonomyMode.DRAFT_ONLY, RobotSourcePolicy.CONTENT_SOURCE, null,
                otherSourceId, RobotSelectionPolicy.OLDEST_UNPROCESSED, null,
                RobotCadenceType.MANUAL_ONLY, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void pausesAndResumesRobot() {
        Robot robot = draftOnlyRobot();
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));

        RobotSummary paused = service.pause(user, robot.getId());
        assertThat(paused.status()).isEqualTo(RobotStatus.PAUSED);

        RobotSummary resumed = service.resume(user, robot.getId());
        assertThat(resumed.status()).isEqualTo(RobotStatus.ACTIVE);
    }

    @Test
    void getForRejectsRobotFromAnotherWorkspace() {
        UUID otherId = UUID.randomUUID();
        when(robots.findByWorkspaceAndId(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFor(user, otherId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateRevalidatesConfigurationLikeCreate() {
        Robot robot = draftOnlyRobot();
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        SocialAccount instagram = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-1", owner, NOW);
        when(socialAccounts.findByWorkspaceAndId(workspace, instagram.getId())).thenReturn(Optional.of(instagram));

        assertThatThrownBy(() -> service.update(user, robot.getId(), new UpdateRobotRequest(
                "Robot", null, RobotAutonomyMode.AUTO_SCHEDULE, instagram.getId(),
                RobotCadenceType.MANUAL_ONLY, null, 60, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void runNowDelegatesToDispatchServiceAndOrchestrator() {
        UUID runId = UUID.randomUUID();
        when(dispatchService.createManualRun(user, someRobotId())).thenReturn(runId);
        RobotRunSummary expected = mock(RobotRunSummary.class);
        when(orchestrator.getFor(user, runId)).thenReturn(expected);

        RobotRunSummary result = service.runNow(user, someRobotId());

        assertThat(result).isSameAs(expected);
    }

    private UUID someRobotId;

    private UUID someRobotId() {
        if (someRobotId == null) {
            someRobotId = UUID.randomUUID();
        }
        return someRobotId;
    }

    private Robot draftOnlyRobot() {
        MediaAsset asset = readyInspectedVideoAsset();
        return new Robot(workspace, "Robot", null, RobotAutonomyMode.DRAFT_ONLY, asset, null,
                RobotCadenceType.MANUAL_ONLY, null, null, 1, owner, NOW);
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
