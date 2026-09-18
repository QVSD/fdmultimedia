package com.fdmultimedia.api.robots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentsources.ContentSource;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import com.fdmultimedia.api.workspaces.WorkspaceRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RobotAutomationDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final RobotRepository robots = mock(RobotRepository.class);
    private final RobotRunRepository runs = mock(RobotRunRepository.class);
    private final RobotProperties properties = new RobotProperties();
    private final RobotSourceSelectionService selectionService = mock(RobotSourceSelectionService.class);
    private final RobotAutomationDispatchService service = new RobotAutomationDispatchService(
            authService, robots, runs, properties, selectionService, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        when(runs.save(any(RobotRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createManualRunRejectedWhenAutomationDisabled() {
        properties.setAutomationEnabled(false);
        Robot robot = draftOnlyRobot();
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));

        assertThatThrownBy(() -> service.createManualRun(user, robot.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
        verify(runs, never()).save(any());
    }

    @Test
    void createManualRunRejectedWhenRobotNotActive() {
        Robot robot = draftOnlyRobot();
        robot.pause(NOW);
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));

        assertThatThrownBy(() -> service.createManualRun(user, robot.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createManualRunCreatesRunAndRecordsLastRunAt() {
        Robot robot = draftOnlyRobot();
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));

        UUID runId = service.createManualRun(user, robot.getId());

        assertThat(runId).isNotNull();
        assertThat(robot.getLastRunAt()).isEqualTo(NOW);
    }

    @Test
    void createManualRunRejectsWhenRobotAlreadyHasActiveRun() {
        Robot robot = draftOnlyRobot();
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        when(runs.existsByRobotAndStatusNotIn(robot, RobotRunStatus.terminalStatuses())).thenReturn(true);

        assertThatThrownBy(() -> service.createManualRun(user, robot.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createManualRunRejectsWhenSourceAlreadyProcessed() {
        Robot robot = draftOnlyRobot();
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        when(runs.existsByRobotAndSourceAssetAndStatus(robot, robot.getSourceAsset(), RobotRunStatus.SUCCEEDED)).thenReturn(true);

        assertThatThrownBy(() -> service.createManualRun(user, robot.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("reason")
                .isEqualTo("SOURCE_ALREADY_PROCESSED");
    }

    @Test
    void createManualRunRejectsWhenDailyLimitReached() {
        Robot robot = draftOnlyRobot();
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        when(runs.countByRobotAndCreatedAtGreaterThanEqual(robot, NOW.truncatedTo(java.time.temporal.ChronoUnit.DAYS))).thenReturn(1L);

        assertThatThrownBy(() -> service.createManualRun(user, robot.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("reason")
                .isEqualTo("DAILY_LIMIT_REACHED");
    }

    @Test
    void createManualRunRejectsWhenWorkspaceLimitReached() {
        Robot robot = draftOnlyRobot();
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        when(runs.countByWorkspaceAndStatusNotIn(workspace, RobotRunStatus.terminalStatuses()))
                .thenReturn((long) properties.getMaxActiveRunsPerWorkspace());

        assertThatThrownBy(() -> service.createManualRun(user, robot.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("reason")
                .isEqualTo("WORKSPACE_LIMIT_REACHED");
    }

    @Test
    void dispatchOneReturnsFalseWhenNothingDue() {
        when(robots.findNextDueForUpdate(NOW)).thenReturn(Optional.empty());

        assertThat(service.dispatchOne()).isFalse();
        verify(runs, never()).save(any());
    }

    @Test
    void dispatchOneCreatesRunAndAdvancesNextRunAt() {
        Robot robot = intervalRobot(6);
        assertThat(robot.getLastRunAt()).isNull();
        when(robots.findNextDueForUpdate(NOW)).thenReturn(Optional.of(robot));

        boolean result = service.dispatchOne();

        assertThat(result).isTrue();
        // advanceNextRunAt sets both lastRunAt and the new nextRunAt; lastRunAt
        // going from null to NOW is the unambiguous proof advancement ran (a
        // fixed Clock makes "nextRunAt changed" itself not a meaningful check,
        // since NOW + 6h computed again is numerically identical either way).
        assertThat(robot.getLastRunAt()).isEqualTo(NOW);
        assertThat(robot.getNextRunAt()).isEqualTo(NOW.plusSeconds(6 * 3600L));
        verify(runs).save(any(RobotRun.class));
    }

    @Test
    void dispatchOneStillAdvancesNextRunAtWhenLimitBlocksTheRun() {
        Robot robot = intervalRobot(6);
        when(robots.findNextDueForUpdate(NOW)).thenReturn(Optional.of(robot));
        when(runs.existsByRobotAndStatusNotIn(robot, RobotRunStatus.terminalStatuses())).thenReturn(true);

        boolean result = service.dispatchOne();

        assertThat(result).isTrue();
        assertThat(robot.getNextRunAt()).isEqualTo(NOW.plusSeconds(6 * 3600L));
        verify(runs, never()).save(any());
    }

    @Test
    void createManualRunRejectsWhenContentSourceIsPaused() {
        ContentSource source = pausedContentSource();
        Robot robot = contentSourceRobot(source);
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));

        assertThatThrownBy(() -> service.createManualRun(user, robot.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("reason")
                .isEqualTo("CONTENT_SOURCE_UNAVAILABLE");
        verify(runs, never()).save(any());
    }

    @Test
    void createManualRunCreatesNoEligibleSourceRunWhenSelectionFindsNothing() {
        ContentSource source = activeContentSource();
        Robot robot = contentSourceRobot(source);
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        when(selectionService.selectNext(robot)).thenReturn(Optional.empty());

        UUID runId = service.createManualRun(user, robot.getId());

        assertThat(runId).isNotNull();
        org.mockito.ArgumentCaptor<RobotRun> captor = org.mockito.ArgumentCaptor.forClass(RobotRun.class);
        verify(runs).save(captor.capture());
        RobotRun saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(RobotRunStatus.FAILED);
        assertThat(saved.getFailureCode()).isEqualTo("NO_ELIGIBLE_SOURCE");
        assertThat(saved.getSourceAsset()).isNull();
        assertThat(saved.getContentSourceId()).isEqualTo(source.getId());
    }

    @Test
    void createManualRunSelectsAssetForContentSourceRobot() {
        ContentSource source = activeContentSource();
        Robot robot = contentSourceRobot(source);
        MediaAsset selected = readyInspectedVideoAsset();
        when(robots.findByWorkspaceAndIdForUpdate(workspace, robot.getId())).thenReturn(Optional.of(robot));
        when(selectionService.selectNext(robot)).thenReturn(Optional.of(selected));

        service.createManualRun(user, robot.getId());

        org.mockito.ArgumentCaptor<RobotRun> captor = org.mockito.ArgumentCaptor.forClass(RobotRun.class);
        verify(runs).save(captor.capture());
        RobotRun saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(RobotRunStatus.RUNNING);
        assertThat(saved.getSourceAsset()).isEqualTo(selected);
        assertThat(saved.getSelectionPolicy()).isEqualTo(RobotSelectionPolicy.OLDEST_UNPROCESSED);
    }

    private ContentSource activeContentSource() {
        return new ContentSource(workspace, "Incoming Tech Videos", null, owner, NOW);
    }

    private ContentSource pausedContentSource() {
        ContentSource source = activeContentSource();
        source.pause(NOW);
        return source;
    }

    private Robot contentSourceRobot(ContentSource source) {
        return new Robot(workspace, "Robot", null, RobotAutonomyMode.DRAFT_ONLY,
                RobotSourcePolicy.CONTENT_SOURCE, null, source, RobotSelectionPolicy.OLDEST_UNPROCESSED, null,
                RobotCadenceType.MANUAL_ONLY, null, null, 1, owner, NOW);
    }

    private Robot draftOnlyRobot() {
        MediaAsset asset = readyInspectedVideoAsset();
        return new Robot(workspace, "Robot", null, RobotAutonomyMode.DRAFT_ONLY, asset, null,
                RobotCadenceType.MANUAL_ONLY, null, null, 1, owner, NOW);
    }

    private Robot intervalRobot(int hours) {
        MediaAsset asset = readyInspectedVideoAsset();
        return new Robot(workspace, "Robot", null, RobotAutonomyMode.DRAFT_ONLY, asset, null,
                RobotCadenceType.INTERVAL, hours, null, 1, owner, NOW);
    }

    private MediaAsset readyInspectedVideoAsset() {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        asset.markImporting(NOW.minusSeconds(5));
        asset.markReady(new MediaImportMetadata("media.mp4", "video/mp4", 12_000, "0".repeat(64), null, null, null, null, null, "mp4"), "media-assets", "storage-key", NOW.minusSeconds(4));
        com.fdmultimedia.api.jobs.Job inspectionJob = new com.fdmultimedia.api.jobs.Job(workspace, com.fdmultimedia.api.jobs.JobType.INSPECT_MEDIA, java.util.Map.of("assetId", asset.getId().toString()), 3, NOW.minusSeconds(3));
        asset.attachInspectionJob(inspectionJob, NOW.minusSeconds(2));
        asset.markInspecting(NOW.minusSeconds(1));
        asset.markInspected(new MediaInspectionMetadata(
                12_000L, 1920, 1080, "h264", "aac", "mp4", new BigDecimal("29.970"), 800_000L, true, true), NOW);
        return asset;
    }
}
