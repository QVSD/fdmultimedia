package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialPlatform;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.contentdrafts.ContentDraftService;
import com.fdmultimedia.api.contentdrafts.ContentDraftStatus;
import com.fdmultimedia.api.contentdrafts.ContentDraftSummary;
import com.fdmultimedia.api.contentsources.ContentSourceRepository;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestion;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionRepository;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionService;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionStatus;
import com.fdmultimedia.api.contentsuggestions.ContentSuggestionSummary;
import com.fdmultimedia.api.experiments.ExperimentService;
import com.fdmultimedia.api.experiments.ExperimentTreatment;
import com.fdmultimedia.api.personas.PersonaRepository;
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
    private final ContentSourceRepository contentSources;
    private final ContentSuggestionService contentSuggestionService;
    private final ContentSuggestionRepository contentSuggestions;
    private final PersonaRepository personas;
    private final ExperimentService experiments;
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
            ContentSourceRepository contentSources,
            ContentSuggestionService contentSuggestionService,
            ContentSuggestionRepository contentSuggestions,
            PersonaRepository personas,
            ExperimentService experiments,
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
        this.contentSources = contentSources;
        this.contentSuggestionService = contentSuggestionService;
        this.contentSuggestions = contentSuggestions;
        this.personas = personas;
        this.experiments = experiments;
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
                case WAITING_FOR_AI -> advanceWaitingForAi(run, principal, now);
                case WAITING_FOR_AI_REVIEW -> advanceWaitingForAiReview(run, principal, now);
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

    /**
     * Returns true once {@code highlightCandidateId} is set (or the run has
     * failed); false while still waiting on analysis. Reads the source asset
     * from the run itself, never the Robot — a CONTENT_SOURCE Robot's own
     * {@code sourceAsset} is always null, and every run already carries the
     * one asset it actually selected (identically for EXISTING_ASSET and
     * CONTENT_SOURCE), so this method never needs to know which policy chose it.
     */
    private boolean resolveCandidate(RobotRun run, Robot robot, AuthenticatedUser principal, Instant now) {
        MediaAsset sourceAsset = run.getSourceAsset();
        if (run.getHighlightAnalysisId() == null) {
            List<HighlightAnalysis> existing = analyses.findByWorkspaceAndAssetOrderByCreatedAtDesc(run.getWorkspace(), sourceAsset);
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
                        principal, sourceAsset.getId(),
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
            case READY -> {
                if (run.getAiPolicySnapshot() == RobotAiPolicy.NO_AI) {
                    takeAutonomyAction(run, draft, principal, now);
                } else {
                    beginAiGeneration(run, draft, principal, now);
                }
            }
            case PUBLISHED -> takeAutonomyAction(run, draft, principal, now);
            case FAILED -> run.markFailed("DRAFT_PREPARATION_FAILED", safe(draft.failureMessage(), "Draft preparation failed"), now);
            case DRAFT, PUBLISHING -> { /* still preparing or already mid-publish from a prior action; keep waiting */ }
        }
    }

    /**
     * The only place an automatic {@code ContentSuggestion} is ever created
     * for this run — reached exactly once, since a successful call
     * transitions {@code run.status} away from WAITING_FOR_DRAFT to
     * WAITING_FOR_AI atomically (same transaction as the suggestion/Job
     * creation itself, both under this run's own row lock), so no later
     * reconciliation pass can re-enter this method for the same run. The
     * {@code contentSuggestionId == null} guard below is defense in depth,
     * not the primary idempotency mechanism; the DB-level
     * {@code content_suggestions_one_robot_suggestion_per_run} unique index
     * is the final backstop.
     */
    private void beginAiGeneration(RobotRun run, ContentDraftSummary draft, AuthenticatedUser principal, Instant now) {
        if (run.getContentSuggestionId() != null) {
            run.markWaitingForAi(now);
            return;
        }
        ContentDraft draftEntity = contentDrafts.findByWorkspaceAndId(run.getWorkspace(), draft.id()).orElse(null);
        if (draftEntity == null) {
            run.markFailed("ROBOT_AI_GENERATION_FAILED", "Draft is no longer available", now);
            return;
        }
        try {
            // Item 28/86: when this run carries a frozen experiment assignment, its
            // variant Persona treatment overrides the Robot's own personaIdSnapshot —
            // resolved from the variant's own frozen columns, never a live Persona lookup.
            ExperimentTreatment treatment = run.getExperimentVariantId() == null ? null
                    : experiments.resolveTreatment(run.getExperimentId(), run.getExperimentAssignmentId(), run.getExperimentVariantId());
            ContentSuggestionSummary suggestion = contentSuggestionService.createForRobot(
                    run.getWorkspace(), draftEntity, run.getPersonaIdSnapshot(),
                    run.getAiLanguageOverrideSnapshot(), run.getAiToneOverrideSnapshot(),
                    run.getId(), run.getRobot().getCreatedByUser(), treatment);
            run.setContentSuggestionId(suggestion.id());
            run.markWaitingForAi(now);
        } catch (ResponseStatusException ex) {
            run.markFailed(mapAiCreateFailureCode(ex.getReason()), safe(ex.getReason(), "AI generation could not be started"), now);
        }
    }

    private String mapAiCreateFailureCode(String reason) {
        return switch (reason == null ? "" : reason) {
            case "AI_DISABLED" -> "ROBOT_AI_DISABLED";
            case "PERSONA_ARCHIVED", "Persona not found" -> "ROBOT_AI_PERSONA_UNAVAILABLE";
            default -> "ROBOT_AI_GENERATION_FAILED";
        };
    }

    /**
     * PENDING/GENERATING: the {@code GENERATE_SOCIAL_COPY} Job owns bounded
     * retries — this method never retries anything itself, only observes.
     * FAILED/DISCARDED: terminal from the Robot's own perspective. READY:
     * either wait for human review (GENERATE_FOR_REVIEW) or apply
     * immediately (GENERATE_AND_APPLY) — the same authoritative Apply path
     * a human uses, never a hand-composed caption here. APPLIED: crash-
     * recovery path — a prior Apply already committed (by this method or by
     * a human, for GENERATE_FOR_REVIEW) but the run never advanced past
     * WAITING_FOR_AI; simply continue rather than re-applying.
     */
    private void advanceWaitingForAi(RobotRun run, AuthenticatedUser principal, Instant now) {
        ContentSuggestion suggestion = contentSuggestions.findById(run.getContentSuggestionId()).orElse(null);
        if (suggestion == null) {
            run.markFailed("ROBOT_AI_GENERATION_FAILED", "The AI suggestion is no longer available", now);
            return;
        }
        switch (suggestion.getStatus()) {
            case PENDING, GENERATING -> { /* still processing; nothing to do until the Job finishes */ }
            case FAILED -> run.markFailed("ROBOT_AI_GENERATION_FAILED", safe(suggestion.getFailureMessage(), "AI content generation failed"), now);
            case DISCARDED -> run.markFailed("ROBOT_AI_SUGGESTION_DISCARDED", "The AI suggestion was discarded", now);
            case READY -> {
                if (run.getAiPolicySnapshot() == RobotAiPolicy.GENERATE_FOR_REVIEW) {
                    run.markWaitingForAiReview(now);
                } else {
                    applyAiSuggestion(run, suggestion, principal, now);
                }
            }
            case APPLIED -> continueAfterAiResolved(run, principal, now);
        }
    }

    /**
     * WAITING_FOR_AI_REVIEW: the AI content review gate — deliberately
     * distinct from the (pre-existing) publishing approval review gate
     * below. READY: still waiting for a human to Apply or Discard through
     * the ordinary ContentSuggestion endpoints; this method never applies
     * on the human's behalf here (that would defeat the point of
     * GENERATE_FOR_REVIEW). APPLIED: the human applied it — continue
     * autonomy. DISCARDED: a controlled terminal outcome, never an infinite
     * wait and never a silent auto-regeneration.
     */
    private void advanceWaitingForAiReview(RobotRun run, AuthenticatedUser principal, Instant now) {
        ContentSuggestion suggestion = contentSuggestions.findById(run.getContentSuggestionId()).orElse(null);
        if (suggestion == null) {
            run.markFailed("ROBOT_AI_GENERATION_FAILED", "The AI suggestion is no longer available", now);
            return;
        }
        switch (suggestion.getStatus()) {
            case READY, PENDING, GENERATING -> { /* still waiting for human review */ }
            case APPLIED -> continueAfterAiResolved(run, principal, now);
            case DISCARDED -> run.markFailed("ROBOT_AI_SUGGESTION_DISCARDED", "The AI suggestion was discarded", now);
            case FAILED -> run.markFailed("ROBOT_AI_GENERATION_FAILED", safe(suggestion.getFailureMessage(), "AI content generation failed"), now);
        }
    }

    /**
     * Reuses the exact authoritative {@code ContentSuggestionService.apply}
     * path a human uses — never a hand-composed caption, never bypassing
     * the fingerprint/staleness check. A stale Draft (edited by a human
     * after generation) fails the run without ever overwriting that edit.
     */
    private void applyAiSuggestion(RobotRun run, ContentSuggestion suggestion, AuthenticatedUser principal, Instant now) {
        try {
            contentSuggestionService.apply(principal, suggestion.getId());
            continueAfterAiResolved(run, principal, now);
        } catch (ResponseStatusException ex) {
            String reason = ex.getReason();
            if ("SUGGESTION_STALE".equals(reason)) {
                run.markFailed("ROBOT_AI_SUGGESTION_STALE", "The Draft changed after AI generation; the human edit was preserved", now);
            } else if ("SUGGESTION_ALREADY_APPLIED".equals(reason)) {
                continueAfterAiResolved(run, principal, now);
            } else {
                run.markFailed("ROBOT_AI_APPLY_FAILED", safe(reason, "Failed to apply the AI suggestion"), now);
            }
        }
    }

    private void continueAfterAiResolved(RobotRun run, AuthenticatedUser principal, Instant now) {
        ContentDraftSummary draft = contentDraftService.getFor(principal, run.getContentDraftId());
        takeAutonomyAction(run, draft, principal, now);
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
        String contentSourceName = run.getContentSourceId() == null
                ? null
                : contentSources.findById(run.getContentSourceId()).map(source -> source.getName()).orElse(null);
        String personaName = run.getPersonaIdSnapshot() == null
                ? null
                : personas.findById(run.getPersonaIdSnapshot()).map(persona -> persona.getName()).orElse(null);
        return new RobotRunSummary(
                run.getId(),
                run.getRobot().getId(),
                run.getRobot().getName(),
                run.getTriggerType(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getSourceAsset() == null ? null : run.getSourceAsset().getId(),
                run.getContentSourceId(),
                contentSourceName,
                run.getSelectionPolicy(),
                run.getHighlightAnalysisId(),
                run.getHighlightCandidateId(),
                run.getContentDraftId(),
                run.getAiPolicySnapshot(),
                run.getPersonaIdSnapshot(),
                personaName,
                run.getContentSuggestionId(),
                run.getExperimentId(),
                run.getExperimentVariantId(),
                run.getExperimentVariantKey(),
                run.getPublishScheduleId(),
                run.getFailureCode(),
                run.getFailureMessage(),
                run.getCreatedAt());
    }
}
