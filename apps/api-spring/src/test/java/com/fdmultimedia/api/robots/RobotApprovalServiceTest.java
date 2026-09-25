package com.fdmultimedia.api.robots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
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
import com.fdmultimedia.api.publishing.PublicationStatus;
import com.fdmultimedia.api.publishschedules.PublishScheduleService;
import com.fdmultimedia.api.publishschedules.PublishScheduleStatus;
import com.fdmultimedia.api.publishschedules.PublishScheduleSummary;
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

class RobotApprovalServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final RobotApprovalRepository approvals = mock(RobotApprovalRepository.class);
    private final ContentDraftRepository contentDrafts = mock(ContentDraftRepository.class);
    private final SocialAccountRepository socialAccounts = mock(SocialAccountRepository.class);
    private final PublishScheduleService publishScheduleService = mock(PublishScheduleService.class);
    private final RobotRunOutputRepository outputs = mock(RobotRunOutputRepository.class);
    private final RobotApprovalService service = new RobotApprovalService(
            authService, approvals, contentDrafts, socialAccounts, publishScheduleService, outputs,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser user;
    private SocialAccount account;
    private ContentDraft draft;
    private Robot robot;
    private RobotRun run;
    private RobotApproval approval;

    @Test
    void approvingOutputSchedulesOnlyThatOutput() {
        RobotRunOutput output = mock(RobotRunOutput.class);
        UUID draftId = draft.getId();
        RobotApproval outputApproval = new RobotApproval(
                workspace, run, output, draftId, account.getId(), NOW.plusSeconds(60), NOW);
        when(approvals.findByWorkspaceAndIdForUpdate(workspace, outputApproval.getId()))
                .thenReturn(Optional.of(outputApproval));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        when(contentDrafts.findByWorkspaceAndId(workspace, draftId)).thenReturn(Optional.of(draft));
        UUID scheduleId = UUID.randomUUID();
        when(publishScheduleService.create(eq(user), eq(draftId), any()))
                .thenReturn(publishScheduleSummary(scheduleId));

        service.approve(user, outputApproval.getId(), null);

        verify(output).scheduled(scheduleId, NOW);
        assertThat(run.getStatus()).isNotEqualTo(RobotRunStatus.SUCCEEDED);
    }

    @Test
    void rejectingOutputDoesNotFailTheParent() {
        RobotRunOutput output = mock(RobotRunOutput.class);
        RobotApproval outputApproval = new RobotApproval(
                workspace, run, output, draft.getId(), account.getId(), NOW.plusSeconds(60), NOW);
        when(approvals.findByWorkspaceAndIdForUpdate(workspace, outputApproval.getId()))
                .thenReturn(Optional.of(outputApproval));

        service.reject(user, outputApproval.getId());

        verify(output).failed("APPROVAL_REJECTED", "The proposed publication was rejected", NOW);
        assertThat(run.getStatus()).isNotEqualTo(RobotRunStatus.FAILED);
    }

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        user = new AuthenticatedUser(owner);
        when(authService.currentMembershipFor(user)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        account = new SocialAccount(workspace, SocialPlatform.TEST, "TEST account", owner, NOW);
        MediaAsset source = readyInspectedVideoAsset();
        robot = new Robot(workspace, "Robot", null, RobotAutonomyMode.REVIEW_REQUIRED, source, account,
                RobotCadenceType.MANUAL_ONLY, null, 60, 1, owner, NOW);
        run = new RobotRun(workspace, robot, RobotRunTriggerType.MANUAL, source, NOW);
        run.markWaitingForDraft(UUID.randomUUID(), NOW);
        approval = new RobotApproval(workspace, run, run.getContentDraftId(), account.getId(), NOW.plusSeconds(3600), NOW);
        draft = mock(ContentDraft.class);
        when(draft.getId()).thenReturn(run.getContentDraftId());
    }

    @Test
    void approveCreatesScheduleAndCompletesRun() {
        when(approvals.findByWorkspaceAndIdForUpdate(workspace, approval.getId())).thenReturn(Optional.of(approval));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        when(contentDrafts.findByWorkspaceAndId(workspace, run.getContentDraftId())).thenReturn(Optional.of(draft));
        UUID scheduleId = UUID.randomUUID();
        when(publishScheduleService.create(eq(user), eq(run.getContentDraftId()), any())).thenReturn(publishScheduleSummary(scheduleId));

        RobotApprovalSummary summary = service.approve(user, approval.getId(), new ApproveRobotApprovalRequest(null));

        assertThat(summary.status()).isEqualTo(RobotApprovalStatus.APPROVED);
        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.SUCCEEDED);
        assertThat(run.getPublishScheduleId()).isEqualTo(scheduleId);
    }

    @Test
    void approveUsesOverrideScheduledForWhenProvided() {
        when(approvals.findByWorkspaceAndIdForUpdate(workspace, approval.getId())).thenReturn(Optional.of(approval));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        when(contentDrafts.findByWorkspaceAndId(workspace, run.getContentDraftId())).thenReturn(Optional.of(draft));
        when(publishScheduleService.create(any(), any(), any())).thenReturn(publishScheduleSummary(UUID.randomUUID()));
        Instant override = NOW.plusSeconds(7200);

        service.approve(user, approval.getId(), new ApproveRobotApprovalRequest(override));

        org.mockito.ArgumentCaptor<com.fdmultimedia.api.publishschedules.CreatePublishScheduleRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.fdmultimedia.api.publishschedules.CreatePublishScheduleRequest.class);
        verify(publishScheduleService).create(eq(user), eq(run.getContentDraftId()), captor.capture());
        assertThat(captor.getValue().scheduledFor()).isEqualTo(override);
    }

    @Test
    void rejectMarksRunFailedWithoutCreatingSchedule() {
        when(approvals.findByWorkspaceAndIdForUpdate(workspace, approval.getId())).thenReturn(Optional.of(approval));

        RobotApprovalSummary summary = service.reject(user, approval.getId());

        assertThat(summary.status()).isEqualTo(RobotApprovalStatus.REJECTED);
        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("APPROVAL_REJECTED");
        verify(publishScheduleService, never()).create(any(), any(), any());
    }

    @Test
    void doubleApproveRejectedOnSecondAttempt() {
        when(approvals.findByWorkspaceAndIdForUpdate(workspace, approval.getId())).thenReturn(Optional.of(approval));
        when(socialAccounts.findByWorkspaceAndId(workspace, account.getId())).thenReturn(Optional.of(account));
        when(contentDrafts.findByWorkspaceAndId(workspace, run.getContentDraftId())).thenReturn(Optional.of(draft));
        when(publishScheduleService.create(any(), any(), any())).thenReturn(publishScheduleSummary(UUID.randomUUID()));

        service.approve(user, approval.getId(), null);

        assertThatThrownBy(() -> service.approve(user, approval.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
        verify(publishScheduleService, org.mockito.Mockito.times(1)).create(any(), any(), any());
    }

    @Test
    void approveRejectsCrossWorkspaceApproval() {
        UUID otherId = UUID.randomUUID();
        when(approvals.findByWorkspaceAndIdForUpdate(workspace, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(user, otherId, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private PublishScheduleSummary publishScheduleSummary(UUID id) {
        return new PublishScheduleSummary(id, run.getContentDraftId(), "Title", UUID.randomUUID(), "media.mp4",
                account.getId(), "TEST account", SocialPlatform.TEST, "caption", NOW.plusSeconds(3600),
                PublishScheduleStatus.SCHEDULED, null, NOW, NOW, null, null, null, null, null);
    }

    private MediaAsset readyInspectedVideoAsset() {
        MediaAsset asset = new MediaAsset(workspace, owner, "https://example.com/media.mp4", NOW);
        asset.markImporting(NOW.minusSeconds(5));
        asset.markReady(new MediaImportMetadata("media.mp4", "video/mp4", 12_000, "0".repeat(64), null, null, null, null, null, "mp4"), "media-assets", "storage-key", NOW.minusSeconds(4));
        Job inspectionJob = new Job(workspace, JobType.INSPECT_MEDIA, java.util.Map.of("assetId", asset.getId().toString()), 3, NOW.minusSeconds(3));
        asset.attachInspectionJob(inspectionJob, NOW.minusSeconds(2));
        asset.markInspecting(NOW.minusSeconds(1));
        asset.markInspected(new MediaInspectionMetadata(
                12_000L, 1920, 1080, "h264", "aac", "mp4", new BigDecimal("29.970"), 800_000L, true, true), NOW);
        return asset;
    }
}
