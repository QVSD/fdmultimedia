package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.experiments.ExperimentAssignment;
import com.fdmultimedia.api.experiments.ExperimentFactor;
import com.fdmultimedia.api.experiments.ExperimentVariantKey;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The durable audit record of one Robot execution — not a Job, not a
 * Publication, not a ContentDraft. Progress within {@link RobotRunStatus#RUNNING}
 * is tracked by which provenance field below is populated, not by additional
 * statuses: {@code highlightAnalysisId} (waiting on analysis) ->
 * {@code highlightCandidateId} (candidate chosen) -> {@code contentDraftId}
 * (draft created). This is reconciled the same way whether triggered by a
 * read, a background poll, or right after creation — never held open on an
 * in-memory thread.
 */
@Entity
@Table(name = "robot_runs")
public class RobotRun {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "robot_id", nullable = false)
    private Robot robot;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private RobotRunTriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RobotRunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_asset_id")
    private MediaAsset sourceAsset;

    @Column(name = "content_source_id")
    private UUID contentSourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_policy")
    private RobotSelectionPolicy selectionPolicy;

    @Column(name = "highlight_analysis_id")
    private UUID highlightAnalysisId;

    @Column(name = "highlight_candidate_id")
    private UUID highlightCandidateId;

    @Column(name = "content_draft_id")
    private UUID contentDraftId;

    /**
     * Immutable snapshot of the Robot's AI configuration, captured once at
     * run creation (see RobotAutomationDispatchService.startRun) — editing
     * the Robot afterward never redirects an in-flight run. personaId is a
     * plain reference (which Persona to resolve when generation actually
     * begins), never the Persona's editorial fields themselves; those are
     * only ever snapshotted onto the ContentSuggestion, exactly as Phase
     * 12B established.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_policy_snapshot", nullable = false)
    private RobotAiPolicy aiPolicySnapshot;

    @Column(name = "persona_id_snapshot")
    private UUID personaIdSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_language_override_snapshot")
    private SuggestionLanguage aiLanguageOverrideSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_tone_override_snapshot")
    private SuggestionTone aiToneOverrideSnapshot;

    /** The one automatic ContentSuggestion this run has created, if any — set exactly once. */
    @Column(name = "content_suggestion_id")
    private UUID contentSuggestionId;

    /**
     * Phase 14A: this run's controlled-experiment assignment, set exactly
     * once at run creation (see RobotAutomationDispatchService.startRun),
     * always before the treatment is ever consumed. The
     * {@code experiment_assignments} row is the source of truth; these are
     * audit-convenience columns, exactly like the AI policy snapshot above.
     * Robot config edits or Experiment pause/resume after this point never
     * change these fields.
     */
    @Column(name = "experiment_id")
    private UUID experimentId;

    @Column(name = "experiment_assignment_id")
    private UUID experimentAssignmentId;

    @Column(name = "experiment_variant_id")
    private UUID experimentVariantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "experiment_variant_key")
    private ExperimentVariantKey experimentVariantKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "experiment_factor")
    private ExperimentFactor experimentFactor;

    @Column(name = "publish_schedule_id")
    private UUID publishScheduleId;

    @Enumerated(EnumType.STRING) @Column(name="highlight_strategy_snapshot",nullable=false)
    private RobotHighlightStrategy highlightStrategySnapshot;
    @Column(name="requested_output_count",nullable=false) private int requestedOutputCount;
    @Column(name="actual_output_count") private Integer actualOutputCount;
    @Column(name="output_spacing_minutes_snapshot",nullable=false) private int outputSpacingMinutesSnapshot;
    @Column(name="highlight_selection_id") private UUID highlightSelectionId;
    @Column(name="output_schedule_base_at") private Instant outputScheduleBaseAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RobotRun() {
    }

    /** EXISTING_ASSET convenience constructor — Phase 11C shape, unchanged. Snapshots the Robot's current AI configuration. */
    public RobotRun(Workspace workspace, Robot robot, RobotRunTriggerType triggerType, MediaAsset sourceAsset, Instant now) {
        this(workspace, robot, triggerType, sourceAsset, null, null, now);
    }

    public RobotRun(
            Workspace workspace,
            Robot robot,
            RobotRunTriggerType triggerType,
            MediaAsset sourceAsset,
            UUID contentSourceId,
            RobotSelectionPolicy selectionPolicy,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.robot = robot;
        this.triggerType = triggerType;
        this.status = RobotRunStatus.RUNNING;
        this.sourceAsset = sourceAsset;
        this.contentSourceId = contentSourceId;
        this.selectionPolicy = selectionPolicy;
        this.startedAt = now;
        this.createdAt = now;
        // Snapshotted once, here, at run creation — never re-read from the
        // live Robot again for this run (see class Javadoc on the fields).
        this.aiPolicySnapshot = robot.getAiPolicy();
        this.personaIdSnapshot = robot.getPersona() == null ? null : robot.getPersona().getId();
        this.aiLanguageOverrideSnapshot = robot.getAiLanguageOverride();
        this.aiToneOverrideSnapshot = robot.getAiToneOverride();
        this.highlightStrategySnapshot = robot.getHighlightStrategy();
        this.requestedOutputCount = robot.getHighlightStrategy() == RobotHighlightStrategy.TOP_DIVERSE_HIGHLIGHTS
                ? robot.getHighlightCount() : 1;
        this.outputSpacingMinutesSnapshot = robot.getOutputSpacingMinutes();
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = startedAt != null ? startedAt : Instant.now();
        }
    }

    public boolean isTerminal() {
        return RobotRunStatus.terminalStatuses().contains(status);
    }

    public void setHighlightAnalysisId(UUID highlightAnalysisId) {
        this.highlightAnalysisId = highlightAnalysisId;
    }

    public void setHighlightCandidateId(UUID highlightCandidateId) {
        this.highlightCandidateId = highlightCandidateId;
    }

    public void markWaitingForDraft(UUID contentDraftId, Instant now) {
        this.contentDraftId = contentDraftId;
        this.status = RobotRunStatus.WAITING_FOR_DRAFT;
    }

    public void markWaitingForReview(Instant now) {
        this.status = RobotRunStatus.WAITING_FOR_REVIEW;
    }

    /** Set exactly once, atomically with the WAITING_FOR_AI transition below — see RobotRunOrchestrator for the idempotency guard. */
    public void setContentSuggestionId(UUID contentSuggestionId) {
        this.contentSuggestionId = contentSuggestionId;
    }

    /** Called at most once, by {@code RobotAutomationDispatchService.startRun}, immediately after {@link ExperimentAssignment} is durably created. */
    public void applyExperimentAssignment(ExperimentAssignment assignment) {
        this.experimentId = assignment.getExperiment().getId();
        this.experimentAssignmentId = assignment.getId();
        this.experimentVariantId = assignment.getExperimentVariant().getId();
        this.experimentVariantKey = assignment.getExperimentVariant().getVariantKey();
        this.experimentFactor = assignment.getFactor();
    }

    public void markWaitingForAi(Instant now) {
        this.status = RobotRunStatus.WAITING_FOR_AI;
    }

    public void markWaitingForAiReview(Instant now) {
        this.status = RobotRunStatus.WAITING_FOR_AI_REVIEW;
    }

    public void setPublishScheduleId(UUID publishScheduleId) {
        this.publishScheduleId = publishScheduleId;
    }

    public void markSucceeded(Instant now) {
        this.status = RobotRunStatus.SUCCEEDED;
        this.finishedAt = now;
    }

    public void bindSelection(UUID selectionId, int actualCount) {
        if (highlightSelectionId != null && !highlightSelectionId.equals(selectionId)) throw new IllegalStateException("Run selection is immutable");
        highlightSelectionId=selectionId; actualOutputCount=actualCount;
    }
    public Instant outputScheduleBase(Instant now, int delayMinutes) {
        if (outputScheduleBaseAt == null) {
            outputScheduleBaseAt = now.plus(delayMinutes, java.time.temporal.ChronoUnit.MINUTES);
        }
        return outputScheduleBaseAt;
    }

    public void markPartiallySucceeded(Instant now) {
        status=RobotRunStatus.PARTIALLY_SUCCEEDED; finishedAt=now;
    }

    public void markFailed(String failureCode, String failureMessage, Instant now) {
        this.status = RobotRunStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = normalize(failureMessage);
        this.finishedAt = now;
    }

    public void markCancelled(Instant now) {
        this.status = RobotRunStatus.CANCELLED;
        this.finishedAt = now;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value.trim();
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public Robot getRobot() { return robot; }
    public RobotRunTriggerType getTriggerType() { return triggerType; }
    public RobotRunStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public MediaAsset getSourceAsset() { return sourceAsset; }
    public UUID getContentSourceId() { return contentSourceId; }
    public RobotSelectionPolicy getSelectionPolicy() { return selectionPolicy; }
    public UUID getHighlightAnalysisId() { return highlightAnalysisId; }
    public UUID getHighlightCandidateId() { return highlightCandidateId; }
    public UUID getContentDraftId() { return contentDraftId; }
    public RobotAiPolicy getAiPolicySnapshot() { return aiPolicySnapshot; }
    public UUID getPersonaIdSnapshot() { return personaIdSnapshot; }
    public SuggestionLanguage getAiLanguageOverrideSnapshot() { return aiLanguageOverrideSnapshot; }
    public SuggestionTone getAiToneOverrideSnapshot() { return aiToneOverrideSnapshot; }
    public UUID getContentSuggestionId() { return contentSuggestionId; }
    public UUID getExperimentId() { return experimentId; }
    public UUID getExperimentAssignmentId() { return experimentAssignmentId; }
    public UUID getExperimentVariantId() { return experimentVariantId; }
    public ExperimentVariantKey getExperimentVariantKey() { return experimentVariantKey; }
    public ExperimentFactor getExperimentFactor() { return experimentFactor; }
    public UUID getPublishScheduleId() { return publishScheduleId; }
    public RobotHighlightStrategy getHighlightStrategySnapshot(){return highlightStrategySnapshot;}
    public int getRequestedOutputCount(){return requestedOutputCount;}
    public Integer getActualOutputCount(){return actualOutputCount;}
    public int getOutputSpacingMinutesSnapshot(){return outputSpacingMinutesSnapshot;}
    public UUID getHighlightSelectionId(){return highlightSelectionId;}
    public Instant getOutputScheduleBaseAt(){return outputScheduleBaseAt;}
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
