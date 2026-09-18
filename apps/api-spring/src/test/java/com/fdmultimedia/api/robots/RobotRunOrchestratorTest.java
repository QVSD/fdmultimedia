package com.fdmultimedia.api.robots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaImportMetadata;
import com.fdmultimedia.api.assets.MediaInspectionMetadata;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.contentdrafts.ContentDraftService;
import com.fdmultimedia.api.contentdrafts.ContentDraftStatus;
import com.fdmultimedia.api.contentdrafts.ContentDraftSummary;
import com.fdmultimedia.api.contentdrafts.ContentDraftWorkflowStage;
import com.fdmultimedia.api.highlights.HighlightAnalysis;
import com.fdmultimedia.api.highlights.HighlightAnalysisRepository;
import com.fdmultimedia.api.highlights.HighlightAnalysisStatus;
import com.fdmultimedia.api.highlights.HighlightAnalysisSummary;
import com.fdmultimedia.api.highlights.HighlightCandidate;
import com.fdmultimedia.api.highlights.HighlightCandidateRepository;
import com.fdmultimedia.api.highlights.HighlightProperties;
import com.fdmultimedia.api.highlights.HighlightService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RobotRunOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    private final AuthService authService = mock(AuthService.class);
    private final RobotRepository robotRepository = mock(RobotRepository.class);
    private final RobotRunRepository runs = mock(RobotRunRepository.class);
    private final HighlightAnalysisRepository analyses = mock(HighlightAnalysisRepository.class);
    private final HighlightCandidateRepository candidates = mock(HighlightCandidateRepository.class);
    private final HighlightService highlightService = mock(HighlightService.class);
    private final HighlightProperties highlightProperties = new HighlightProperties();
    private final ContentDraftService contentDraftService = mock(ContentDraftService.class);
    private final ContentDraftRepository contentDrafts = mock(ContentDraftRepository.class);
    private final RobotApprovalRepository approvals = mock(RobotApprovalRepository.class);
    private final PublishScheduleService publishScheduleService = mock(PublishScheduleService.class);
    private final RobotRunOrchestrator orchestrator = new RobotRunOrchestrator(
            authService, robotRepository, runs, analyses, candidates, highlightService, highlightProperties,
            contentDraftService, contentDrafts, approvals, publishScheduleService, Clock.fixed(NOW, ZoneOffset.UTC));

    private Workspace workspace;
    private AppUser owner;
    private AuthenticatedUser ownerPrincipal;
    private MediaAsset sourceAsset;

    @BeforeEach
    void setUp() {
        workspace = new Workspace("FD Multimedia", "fdm");
        owner = new AppUser("owner@example.com", "$2a$10$hash", "Owner");
        ownerPrincipal = new AuthenticatedUser(owner);
        sourceAsset = readyInspectedVideoAsset();
        when(approvals.findByRobotRun(any())).thenReturn(Optional.empty());
    }

    @Test
    void reconcileOneNoOpsWhenRunNotFoundOrLocked() {
        UUID runId = UUID.randomUUID();
        when(runs.findByIdForUpdateSkipLocked(runId)).thenReturn(Optional.empty());

        orchestrator.reconcileOne(runId);

        verify(highlightService, never()).createAnalysis(any(), any(), any());
    }

    @Test
    void runningTriggersNewAnalysisWhenNoneExists() {
        Robot robot = draftOnlyRobot();
        RobotRun run = newRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(analyses.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, sourceAsset)).thenReturn(List.of());
        UUID analysisId = UUID.randomUUID();
        when(highlightService.createAnalysis(any(), eq(sourceAsset.getId()), any()))
                .thenReturn(highlightAnalysisSummary(analysisId, HighlightAnalysisStatus.PENDING));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getHighlightAnalysisId()).isEqualTo(analysisId);
        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.RUNNING);
        verify(contentDraftService, never()).createFromHighlightCandidate(any(), any());
    }

    @Test
    void runningReusesExistingSucceededAnalysisAndCreatesDraftInOnePass() {
        Robot robot = draftOnlyRobot();
        RobotRun run = newRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        HighlightAnalysis analysis = succeededAnalysis();
        when(analyses.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, sourceAsset)).thenReturn(List.of(analysis));
        HighlightCandidate candidate = candidateOf(analysis, 1);
        when(candidates.findByAnalysisOrderByRankAsc(analysis)).thenReturn(List.of(candidate));
        UUID draftId = UUID.randomUUID();
        when(contentDraftService.createFromHighlightCandidate(any(), eq(candidate.getId())))
                .thenReturn(draftSummary(draftId, ContentDraftStatus.DRAFT, ContentDraftWorkflowStage.CLIP_PENDING));
        ContentDraft draftEntity = mock(ContentDraft.class);
        when(contentDrafts.findByWorkspaceAndId(workspace, draftId)).thenReturn(Optional.of(draftEntity));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getHighlightAnalysisId()).isEqualTo(analysis.getId());
        assertThat(run.getHighlightCandidateId()).isEqualTo(candidate.getId());
        assertThat(run.getContentDraftId()).isEqualTo(draftId);
        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.WAITING_FOR_DRAFT);
        verify(draftEntity).attachRobotRun(run.getId());
    }

    @Test
    void runningWaitsWhileReusableAnalysisIsStillPending() {
        Robot robot = draftOnlyRobot();
        RobotRun run = newRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        HighlightAnalysis analysis = pendingAnalysis();
        when(analyses.findByWorkspaceAndAssetOrderByCreatedAtDesc(workspace, sourceAsset)).thenReturn(List.of(analysis));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getHighlightAnalysisId()).isEqualTo(analysis.getId());
        assertThat(run.getHighlightCandidateId()).isNull();
        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.RUNNING);
    }

    @Test
    void runningMarksFailedWhenAnalysisFails() {
        Robot robot = draftOnlyRobot();
        RobotRun run = newRun(robot);
        HighlightAnalysis analysis = pendingAnalysis();
        run.setHighlightAnalysisId(analysis.getId());
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        analysis.markFailed("BAD", "boom", NOW);
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("ANALYSIS_FAILED");
    }

    @Test
    void runningMarksFailedWhenNoCandidates() {
        Robot robot = draftOnlyRobot();
        RobotRun run = newRun(robot);
        HighlightAnalysis analysis = succeededAnalysis();
        run.setHighlightAnalysisId(analysis.getId());
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(analyses.findById(analysis.getId())).thenReturn(Optional.of(analysis));
        when(candidates.findByAnalysisOrderByRankAsc(analysis)).thenReturn(List.of());

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("NO_HIGHLIGHT_CANDIDATE");
    }

    @Test
    void runningMarksFailedWhenDraftCreationFails() {
        Robot robot = draftOnlyRobot();
        RobotRun run = newRun(robot);
        HighlightAnalysis analysis = succeededAnalysis();
        HighlightCandidate candidate = candidateOf(analysis, 1);
        run.setHighlightAnalysisId(analysis.getId());
        run.setHighlightCandidateId(candidate.getId());
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(contentDraftService.createFromHighlightCandidate(any(), eq(candidate.getId())))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "not eligible"));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("DRAFT_PREPARATION_FAILED");
    }

    @Test
    void waitingForDraftStaysPutWhileDraftStillPreparing() {
        Robot robot = draftOnlyRobot();
        RobotRun run = waitingForDraftRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(contentDraftService.getFor(any(), eq(run.getContentDraftId())))
                .thenReturn(draftSummary(run.getContentDraftId(), ContentDraftStatus.DRAFT, ContentDraftWorkflowStage.VERTICAL_PENDING));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.WAITING_FOR_DRAFT);
    }

    @Test
    void waitingForDraftFailsWhenDraftFails() {
        Robot robot = draftOnlyRobot();
        RobotRun run = waitingForDraftRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(contentDraftService.getFor(any(), eq(run.getContentDraftId())))
                .thenReturn(failedDraftSummary(run.getContentDraftId(), "encode failed"));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("DRAFT_PREPARATION_FAILED");
    }

    @Test
    void draftOnlySucceedsOnceDraftIsReady() {
        Robot robot = draftOnlyRobot();
        RobotRun run = waitingForDraftRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(contentDraftService.getFor(any(), eq(run.getContentDraftId())))
                .thenReturn(draftSummary(run.getContentDraftId(), ContentDraftStatus.READY, ContentDraftWorkflowStage.READY));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.SUCCEEDED);
        verify(approvals, never()).save(any());
        verify(publishScheduleService, never()).create(any(), any(), any());
    }

    @Test
    void reviewRequiredCreatesExactlyOnePendingApproval() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TEST, "TEST", owner, NOW);
        Robot robot = reviewRequiredRobot(account, 60);
        RobotRun run = waitingForDraftRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(contentDraftService.getFor(any(), eq(run.getContentDraftId())))
                .thenReturn(draftSummary(run.getContentDraftId(), ContentDraftStatus.READY, ContentDraftWorkflowStage.READY));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.WAITING_FOR_REVIEW);
        verify(approvals, times(1)).save(any(RobotApproval.class));
        verify(publishScheduleService, never()).create(any(), any(), any());
    }

    @Test
    void reviewRequiredRepeatedReconcileNeverDuplicatesApproval() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TEST, "TEST", owner, NOW);
        Robot robot = reviewRequiredRobot(account, 60);
        RobotRun run = waitingForDraftRun(robot);
        run.markWaitingForReview(NOW);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));

        orchestrator.reconcileOne(run.getId());
        orchestrator.reconcileOne(run.getId());

        verify(approvals, never()).save(any());
        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.WAITING_FOR_REVIEW);
    }

    @Test
    void autoScheduleCreatesExactlyOnePublishSchedule() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TEST, "TEST", owner, NOW);
        Robot robot = autoScheduleRobot(account, 60);
        RobotRun run = waitingForDraftRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(contentDraftService.getFor(any(), eq(run.getContentDraftId())))
                .thenReturn(draftSummary(run.getContentDraftId(), ContentDraftStatus.READY, ContentDraftWorkflowStage.READY));
        UUID scheduleId = UUID.randomUUID();
        when(publishScheduleService.create(any(), eq(run.getContentDraftId()), any()))
                .thenReturn(publishScheduleSummary(scheduleId));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.SUCCEEDED);
        assertThat(run.getPublishScheduleId()).isEqualTo(scheduleId);
        verify(publishScheduleService, times(1)).create(any(), any(), any());
    }

    @Test
    void autoScheduleRepeatedReconcileNeverDuplicatesSchedule() {
        SocialAccount account = new SocialAccount(workspace, SocialPlatform.TEST, "TEST", owner, NOW);
        Robot robot = autoScheduleRobot(account, 60);
        RobotRun run = waitingForDraftRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(contentDraftService.getFor(any(), eq(run.getContentDraftId())))
                .thenReturn(draftSummary(run.getContentDraftId(), ContentDraftStatus.READY, ContentDraftWorkflowStage.READY));
        when(publishScheduleService.create(any(), any(), any())).thenReturn(publishScheduleSummary(UUID.randomUUID()));

        orchestrator.reconcileOne(run.getId());
        orchestrator.reconcileOne(run.getId());

        verify(publishScheduleService, times(1)).create(any(), any(), any());
    }

    @Test
    void autoScheduleDefensivelyRejectsNonTestProviderEvenIfConfigured() {
        SocialAccount instagram = new SocialAccount(workspace, SocialPlatform.INSTAGRAM, "creator", "ig-1", owner, NOW);
        Robot robot = autoScheduleRobot(instagram, 60);
        RobotRun run = waitingForDraftRun(robot);
        when(runs.findByIdForUpdateSkipLocked(run.getId())).thenReturn(Optional.of(run));
        when(contentDraftService.getFor(any(), eq(run.getContentDraftId())))
                .thenReturn(draftSummary(run.getContentDraftId(), ContentDraftStatus.READY, ContentDraftWorkflowStage.READY));

        orchestrator.reconcileOne(run.getId());

        assertThat(run.getStatus()).isEqualTo(RobotRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("AUTONOMOUS_PROVIDER_NOT_ALLOWED");
        verify(publishScheduleService, never()).create(any(), any(), any());
    }

    @Test
    void getForRejectsRunFromAnotherWorkspace() {
        when(authService.currentMembershipFor(ownerPrincipal)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        UUID otherId = UUID.randomUUID();
        when(runs.findByWorkspaceAndId(workspace, otherId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.getFor(ownerPrincipal, otherId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cancelRejectsAlreadyTerminalRun() {
        when(authService.currentMembershipFor(ownerPrincipal)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        Robot robot = draftOnlyRobot();
        RobotRun run = newRun(robot);
        run.markSucceeded(NOW);
        when(runs.findByWorkspaceAndId(workspace, run.getId())).thenReturn(Optional.of(run));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.cancel(ownerPrincipal, run.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancelMarksNonTerminalRunCancelled() {
        when(authService.currentMembershipFor(ownerPrincipal)).thenReturn(new WorkspaceMembership(workspace, owner, WorkspaceRole.OWNER));
        Robot robot = draftOnlyRobot();
        RobotRun run = newRun(robot);
        when(runs.findByWorkspaceAndId(workspace, run.getId())).thenReturn(Optional.of(run));

        RobotRunSummary summary = orchestrator.cancel(ownerPrincipal, run.getId());

        assertThat(summary.status()).isEqualTo(RobotRunStatus.CANCELLED);
    }

    // ---- helpers ----

    private Robot draftOnlyRobot() {
        return new Robot(workspace, "Robot", null, RobotAutonomyMode.DRAFT_ONLY, sourceAsset, null,
                RobotCadenceType.MANUAL_ONLY, null, null, 1, owner, NOW);
    }

    private Robot reviewRequiredRobot(SocialAccount account, int delayMinutes) {
        return new Robot(workspace, "Robot", null, RobotAutonomyMode.REVIEW_REQUIRED, sourceAsset, account,
                RobotCadenceType.MANUAL_ONLY, null, delayMinutes, 1, owner, NOW);
    }

    private Robot autoScheduleRobot(SocialAccount account, int delayMinutes) {
        return new Robot(workspace, "Robot", null, RobotAutonomyMode.AUTO_SCHEDULE, sourceAsset, account,
                RobotCadenceType.MANUAL_ONLY, null, delayMinutes, 1, owner, NOW);
    }

    private RobotRun newRun(Robot robot) {
        return new RobotRun(workspace, robot, RobotRunTriggerType.MANUAL, sourceAsset, NOW);
    }

    private RobotRun waitingForDraftRun(Robot robot) {
        RobotRun run = newRun(robot);
        run.setHighlightAnalysisId(UUID.randomUUID());
        run.setHighlightCandidateId(UUID.randomUUID());
        run.markWaitingForDraft(UUID.randomUUID(), NOW);
        return run;
    }

    private HighlightAnalysis succeededAnalysis() {
        Job job = new Job(workspace, JobType.ANALYZE_HIGHLIGHTS, Map.of(), 3, NOW);
        HighlightAnalysis analysis = new HighlightAnalysis(workspace, sourceAsset, job, "DETERMINISTIC_V1", "v1", NOW);
        analysis.markRunning(NOW);
        analysis.markSucceeded(NOW);
        return analysis;
    }

    private HighlightAnalysis pendingAnalysis() {
        Job job = new Job(workspace, JobType.ANALYZE_HIGHLIGHTS, Map.of(), 3, NOW);
        return new HighlightAnalysis(workspace, sourceAsset, job, "DETERMINISTIC_V1", "v1", NOW);
    }

    private HighlightCandidate candidateOf(HighlightAnalysis analysis, int rank) {
        return new HighlightCandidate(analysis, 1000, 6000, new BigDecimal("0.9"), "reason", rank, NOW);
    }

    private HighlightAnalysisSummary highlightAnalysisSummary(UUID id, HighlightAnalysisStatus status) {
        return new HighlightAnalysisSummary(id, sourceAsset.getId(), status, UUID.randomUUID(), "DETERMINISTIC_V1", "v1",
                null, null, NOW, NOW, null, List.of());
    }

    private ContentDraftSummary draftSummary(UUID id, ContentDraftStatus status, ContentDraftWorkflowStage stage) {
        return new ContentDraftSummary(id, sourceAsset.getId(), sourceAsset.getId(), "media.mp4", null, null, null,
                status, stage, null, null, null, NOW, NOW, null, List.of(), null);
    }

    private ContentDraftSummary failedDraftSummary(UUID id, String failureMessage) {
        return new ContentDraftSummary(id, sourceAsset.getId(), sourceAsset.getId(), "media.mp4", null, null, null,
                ContentDraftStatus.FAILED, ContentDraftWorkflowStage.CLIP_PENDING, null, "FFMPEG_FAILED", failureMessage,
                NOW, NOW, null, List.of(), null);
    }

    private PublishScheduleSummary publishScheduleSummary(UUID id) {
        return new PublishScheduleSummary(id, UUID.randomUUID(), "Title", sourceAsset.getId(), "media.mp4",
                UUID.randomUUID(), "TEST account", SocialPlatform.TEST, "caption", NOW.plusSeconds(3600),
                PublishScheduleStatus.SCHEDULED, null, NOW, NOW, null, null, null, null, null);
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
