package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.contentdrafts.ContentDraftService;
import com.fdmultimedia.api.contentdrafts.ContentDraftStatus;
import com.fdmultimedia.api.contentdrafts.ContentDraftSummary;
import com.fdmultimedia.api.highlights.CreateHighlightAnalysisRequest;
import com.fdmultimedia.api.highlights.HighlightAnalysis;
import com.fdmultimedia.api.highlights.HighlightAnalysisRepository;
import com.fdmultimedia.api.highlights.HighlightAnalysisStatus;
import com.fdmultimedia.api.highlights.HighlightAnalysisSummary;
import com.fdmultimedia.api.highlights.HighlightCandidate;
import com.fdmultimedia.api.highlights.HighlightCandidateRepository;
import com.fdmultimedia.api.highlights.HighlightProperties;
import com.fdmultimedia.api.highlights.HighlightService;
import com.fdmultimedia.api.publishschedules.CreatePublishScheduleRequest;
import com.fdmultimedia.api.publishschedules.PublishScheduleService;
import com.fdmultimedia.api.publishschedules.PublishScheduleSummary;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Advances a {@link RobotRun} exactly one bounded step per call — analysis
 * reuse/trigger, candidate selection, Draft creation, or an autonomy action
 * — never holding a thread open waiting for FFmpeg, analysis, or human
 * approval. It never touches Jobs, FFmpeg, or Worker mechanics itself:
 * {@code contentDraftService.getFor(...)} already reconciles the Draft's own
 * Phase 11A clip/vertical workflow as a side effect of being read, so this
 * class only ever asks "is the Draft ready yet?" through the existing API.
 *
 * <p>Safe to call repeatedly (a read, a restart, a background poll) — the
 * run's own nullable provenance fields are the idempotency guard: once
 * {@code highlightCandidateId}/{@code contentDraftId}/{@code publishScheduleId}
 * is set, that step is never repeated.
 */
@Service
public class RobotRunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RobotRunOrchestrator.class);

    private final AuthService authService;
    private final RobotRepository robotRepository;
    private final RobotRunRepository runs;
    private final HighlightAnalysisRepository analyses;
    private final HighlightCandidateRepository candidates;
    private final HighlightService highlightService;
    private final HighlightProperties highlightProperties;
    private final ContentDraftService contentDraftService;
    private final ContentDraftRepository contentDrafts;
    private final RobotApprovalRepository approvals;
    private final PublishScheduleService publishScheduleService;
    private final Clock clock;

    public RobotRunOrchestrator(
            AuthService authService,
            RobotRepository robotRepository,
            RobotRunRepository runs,
            HighlightAnalysisRepository analyses,
            HighlightCandidateRepository candidates,
            HighlightService highlightService,
            HighlightProperties highlightProperties,
            ContentDraftService contentDraftService,
            ContentDraftRepository contentDrafts,
            RobotApprovalRepository approvals,
            PublishScheduleService publishScheduleService,
            Clock clock) {
        this.authService = authService;
        this.robotRepository = robotRepository;
        this.runs = runs;
        this.analyses = analyses;
        this.candidates = candidates;
        this.highlightService = highlightService;
        this.highlightProperties = highlightProperties;
        this.contentDraftService = contentDraftService;
        this.contentDrafts = contentDrafts;
        this.approvals = approvals;
        this.publishScheduleService = publishScheduleService;
        this.clock = clock;
    }

    /** Entry point for the background poller — a different bean than this one, so its own transaction always opens correctly. */
    @Transactional
    public void reconcileOne(UUID runId) {
        runs.findByIdForUpdateSkipLocked(runId).ifPresent(this::doReconcile);
    }

    @Transactional
    public RobotRunSummary getFor(AuthenticatedUser principal, UUID runId) {
        Workspace workspace = currentWorkspace(principal);
        RobotRun run = runs.findByWorkspaceAndId(workspace, runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot run not found"));
        runs.findByIdForUpdateSkipLocked(runId).ifPresent(this::doReconcile);
        return toSummary(run);
    }

    @Transactional
    public List<RobotRunSummary> listFor(AuthenticatedUser principal, UUID robotIdFilter) {
        Workspace workspace = currentWorkspace(principal);
        List<RobotRun> rows;
        if (robotIdFilter != null) {
            Robot robot = robotRepository.findByWorkspaceAndId(workspace, robotIdFilter)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot not found"));
            rows = runs.findByRobotOrderByCreatedAtDesc(robot);
        } else {
            rows = runs.findByWorkspaceOrderByCreatedAtDesc(workspace);
        }
        rows.forEach(r -> {
            if (!r.isTerminal()) {
                runs.findByIdForUpdateSkipLocked(r.getId()).ifPresent(this::doReconcile);
            }
        });
        return rows.stream().map(this::toSummary).toList();
    }

    @Transactional
    public RobotRunSummary cancel(AuthenticatedUser principal, UUID runId) {
        Workspace workspace = currentWorkspace(principal);
        RobotRun run = runs.findByWorkspaceAndId(workspace, runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Robot run not found"));
        if (run.isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is already terminal");
        }
        run.markCancelled(Instant.now(clock));
        return toSummary(run);
    }

    private void doReconcile(RobotRun run) {
        if (run.isTerminal()) {
            return;
        }
        AuthenticatedUser principal = new AuthenticatedUser(run.getRobot().getCreatedByUser());
        Instant now = Instant.now(clock);
        try {
            switch (run.getStatus()) {
                case RUNNING -> advanceRunning(run, principal, now);
                case WAITING_FOR_DRAFT -> advanceWaitingForDraft(run, principal, now);
                case WAITING_FOR_REVIEW -> { /* human action required; nothing to auto-advance */ }
                default -> { /* terminal; unreachable due to the guard above */ }
            }
        } catch (RuntimeException ex) {
            log.error("Unexpected error reconciling robot run {}", run.getId(), ex);
            run.markFailed("RUN_RECONCILIATION_ERROR", "Unexpected error while advancing this run", now);
        }
    }

    private void advanceRunning(RobotRun run, AuthenticatedUser principal, Instant now) {
        Robot robot = run.getRobot();
        if (run.getHighlightCandidateId() == null) {
            boolean candidateReady = resolveCandidate(run, robot, principal, now);
            if (!candidateReady) {
                return;
            }
        }
        if (run.getContentDraftId() == null) {
            createDraft(run, principal, now);
        }
    }

    /** Returns true once {@code highlightCandidateId} is set (or the run has failed); false while still waiting on analysis. */
    private boolean resolveCandidate(RobotRun run, Robot robot, AuthenticatedUser principal, Instant now) {
        if (run.getHighlightAnalysisId() == null) {
            List<HighlightAnalysis> existing = analyses.findByWorkspaceAndAssetOrderByCreatedAtDesc(run.getWorkspace(), robot.getSourceAsset());
            Optional<HighlightAnalysis> reusable = existing.stream()
                    .filter(a -> highlightProperties.getDeterministicAnalyzerType().equals(a.getAnalyzerType()))
                    .filter(a -> a.getStatus() != HighlightAnalysisStatus.FAILED)
                    .findFirst();
            if (reusable.isPresent()) {
                HighlightAnalysis analysis = reusable.get();
                run.setHighlightAnalysisId(analysis.getId());
                if (analysis.getStatus() != HighlightAnalysisStatus.SUCCEEDED) {
                    return false;
                }
                return pickCandidateFrom(run, analysis, now);
            }
            HighlightAnalysisSummary created;
            try {
                created = highlightService.createAnalysis(
                        principal, robot.getSourceAsset().getId(),
                        new CreateHighlightAnalysisRequest(highlightProperties.getDeterministicAnalyzerType()));
            } catch (ResponseStatusException ex) {
                run.markFailed("SOURCE_UNAVAILABLE", ex.getReason(), now);
                return false;
            }
            run.setHighlightAnalysisId(created.id());
            return false;
        }
        HighlightAnalysis analysis = analyses.findById(run.getHighlightAnalysisId()).orElse(null);
        if (analysis == null) {
            run.markFailed("ANALYSIS_FAILED", "Highlight analysis is no longer available", now);
            return false;
        }
        if (analysis.getStatus() == HighlightAnalysisStatus.PENDING || analysis.getStatus() == HighlightAnalysisStatus.RUNNING) {
            return false;
        }
        if (analysis.getStatus() == HighlightAnalysisStatus.FAILED) {
            run.markFailed("ANALYSIS_FAILED", safe(analysis.getErrorMessage(), "Highlight analysis failed"), now);
            return false;
        }
        return pickCandidateFrom(run, analysis, now);
    }

    private boolean pickCandidateFrom(RobotRun run, HighlightAnalysis analysis, Instant now) {
        List<HighlightCandidate> ranked = candidates.findByAnalysisOrderByRankAsc(analysis);
        if (ranked.isEmpty()) {
            run.markFailed("NO_HIGHLIGHT_CANDIDATE", "No highlight candidates were found for this source", now);
            return false;
        }
        run.setHighlightCandidateId(ranked.get(0).getId());
        return true;
    }

    private void createDraft(RobotRun run, AuthenticatedUser principal, Instant now) {
        ContentDraftSummary draft;
        try {
            draft = contentDraftService.createFromHighlightCandidate(principal, run.getHighlightCandidateId());
        } catch (ResponseStatusException ex) {
            run.markFailed("DRAFT_PREPARATION_FAILED", ex.getReason(), now);
            return;
        }
        contentDrafts.findByWorkspaceAndId(run.getWorkspace(), draft.id()).ifPresent(entity -> entity.attachRobotRun(run.getId()));
        run.markWaitingForDraft(draft.id(), now);
    }

    private void advanceWaitingForDraft(RobotRun run, AuthenticatedUser principal, Instant now) {
        ContentDraftSummary draft = contentDraftService.getFor(principal, run.getContentDraftId());
        switch (draft.status()) {
            case READY, PUBLISHED -> takeAutonomyAction(run, draft, principal, now);
            case FAILED -> run.markFailed("DRAFT_PREPARATION_FAILED", safe(draft.failureMessage(), "Draft preparation failed"), now);
            case DRAFT, PUBLISHING -> { /* still preparing or already mid-publish from a prior action; keep waiting */ }
        }
    }

    private void takeAutonomyAction(RobotRun run, ContentDraftSummary draft, AuthenticatedUser principal, Instant now) {
        Robot robot = run.getRobot();
        switch (robot.getAutonomyMode()) {
            case DRAFT_ONLY -> run.markSucceeded(now);
            case REVIEW_REQUIRED -> createApprovalIfAbsent(run, robot, draft, now);
            case AUTO_SCHEDULE -> autoSchedule(run, robot, draft, principal, now);
        }
    }

    private void createApprovalIfAbsent(RobotRun run, Robot robot, ContentDraftSummary draft, Instant now) {
        if (approvals.findByRobotRun(run).isPresent()) {
            run.markWaitingForReview(now);
            return;
        }
        SocialAccount account = robot.getTargetSocialAccount();
        if (account == null) {
            run.markFailed("SOCIAL_ACCOUNT_UNAVAILABLE", "Robot has no target account configured", now);
            return;
        }
        Instant proposed = robot.getScheduleDelayMinutes() != null
                ? now.plus(robot.getScheduleDelayMinutes(), ChronoUnit.MINUTES)
                : null;
        approvals.save(new RobotApproval(run.getWorkspace(), run, draft.id(), account.getId(), proposed, now));
        run.markWaitingForReview(now);
    }

    private void autoSchedule(RobotRun run, Robot robot, ContentDraftSummary draft, AuthenticatedUser principal, Instant now) {
        SocialAccount account = robot.getTargetSocialAccount();
        if (account == null) {
            run.markFailed("SOCIAL_ACCOUNT_UNAVAILABLE", "Robot has no target account configured", now);
            return;
        }
        // Defensive re-check: RobotService already rejects this combination at
        // configuration time, but a run may have been created before an
        // account was somehow reassigned; never trust configuration-time
        // validation alone for an autonomous, unattended action.
        if (account.getPlatform() != SocialPlatform.TEST) {
            run.markFailed("AUTONOMOUS_PROVIDER_NOT_ALLOWED", "Automatic scheduling is only allowed for the TEST provider", now);
            return;
        }
        Instant scheduledFor = now.plus(robot.getScheduleDelayMinutes(), ChronoUnit.MINUTES);
        try {
            PublishScheduleSummary schedule = publishScheduleService.create(
                    principal, draft.id(), new CreatePublishScheduleRequest(account.getId(), scheduledFor));
            run.setPublishScheduleId(schedule.id());
            run.markSucceeded(now);
        } catch (ResponseStatusException ex) {
            run.markFailed("SCHEDULE_CREATION_FAILED", ex.getReason(), now);
        }
    }

    private String safe(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private RobotRunSummary toSummary(RobotRun run) {
        return new RobotRunSummary(
                run.getId(),
                run.getRobot().getId(),
                run.getRobot().getName(),
                run.getTriggerType(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getSourceAsset().getId(),
                run.getHighlightAnalysisId(),
                run.getHighlightCandidateId(),
                run.getContentDraftId(),
                run.getPublishScheduleId(),
                run.getFailureCode(),
                run.getFailureMessage(),
                run.getCreatedAt());
    }
}
