package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.contentsources.ContentSource;
import com.fdmultimedia.api.contentsuggestions.SuggestionLanguage;
import com.fdmultimedia.api.contentsuggestions.SuggestionTone;
import com.fdmultimedia.api.personas.Persona;
import com.fdmultimedia.api.users.AppUser;
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
 * A persistent logical automation owned by a workspace — "what should
 * happen," never "where compute happens." A Robot is not a Worker, not a
 * Job, and not tied 1:1 to any physical machine; it orchestrates the
 * existing ContentDraft/PublishSchedule/Publication/Job pipeline through
 * {@code RobotRun}s rather than doing any work itself.
 */
@Entity
@Table(name = "robots")
public class Robot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RobotStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "autonomy_mode", nullable = false)
    private RobotAutonomyMode autonomyMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "highlight_strategy", nullable = false)
    private RobotHighlightStrategy highlightStrategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_policy", nullable = false)
    private RobotSourcePolicy sourcePolicy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_asset_id")
    private MediaAsset sourceAsset;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_source_id")
    private ContentSource contentSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_policy")
    private RobotSelectionPolicy selectionPolicy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_social_account_id")
    private SocialAccount targetSocialAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence_type", nullable = false)
    private RobotCadenceType cadenceType;

    @Column(name = "cadence_interval_hours")
    private Integer cadenceIntervalHours;

    @Column(name = "schedule_delay_minutes")
    private Integer scheduleDelayMinutes;

    @Column(name = "max_runs_per_day", nullable = false)
    private int maxRunsPerDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_policy", nullable = false)
    private RobotAiPolicy aiPolicy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "persona_id")
    private Persona persona;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_language_override")
    private SuggestionLanguage aiLanguageOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_tone_override")
    private SuggestionTone aiToneOverride;

    /**
     * Phase 14A opt-in: a plain reference, never a JPA relationship, so this
     * package stays free of a compile-time dependency on
     * {@code com.fdmultimedia.api.experiments}. When set, an experiment
     * variant's frozen Persona treatment overrides {@link #persona} for
     * experimental runs (see RobotRunOrchestrator) — {@link #persona} itself
     * is never erased, so it is used again unchanged if the Experiment is
     * later detached.
     */
    @Column(name = "experiment_id")
    private UUID experimentId;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private AppUser createdByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Robot() {
    }

    /** EXISTING_ASSET convenience constructor — Phase 11C shape, unchanged. NO_AI/no Persona. */
    public Robot(
            Workspace workspace,
            String name,
            String description,
            RobotAutonomyMode autonomyMode,
            MediaAsset sourceAsset,
            SocialAccount targetSocialAccount,
            RobotCadenceType cadenceType,
            Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes,
            int maxRunsPerDay,
            AppUser createdByUser,
            Instant now) {
        this(workspace, name, description, autonomyMode, RobotSourcePolicy.EXISTING_ASSET, sourceAsset, null, null,
                targetSocialAccount, cadenceType, cadenceIntervalHours, scheduleDelayMinutes, maxRunsPerDay, createdByUser, now);
    }

    /** Phase 11D shape, unchanged — NO_AI/no Persona. */
    public Robot(
            Workspace workspace,
            String name,
            String description,
            RobotAutonomyMode autonomyMode,
            RobotSourcePolicy sourcePolicy,
            MediaAsset sourceAsset,
            ContentSource contentSource,
            RobotSelectionPolicy selectionPolicy,
            SocialAccount targetSocialAccount,
            RobotCadenceType cadenceType,
            Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes,
            int maxRunsPerDay,
            AppUser createdByUser,
            Instant now) {
        this(workspace, name, description, autonomyMode, sourcePolicy, sourceAsset, contentSource, selectionPolicy,
                targetSocialAccount, cadenceType, cadenceIntervalHours, scheduleDelayMinutes, maxRunsPerDay,
                RobotAiPolicy.NO_AI, null, null, null, createdByUser, now);
    }

    /** Phase 12C: full shape including the independent AI enrichment policy axis. */
    public Robot(
            Workspace workspace,
            String name,
            String description,
            RobotAutonomyMode autonomyMode,
            RobotSourcePolicy sourcePolicy,
            MediaAsset sourceAsset,
            ContentSource contentSource,
            RobotSelectionPolicy selectionPolicy,
            SocialAccount targetSocialAccount,
            RobotCadenceType cadenceType,
            Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes,
            int maxRunsPerDay,
            RobotAiPolicy aiPolicy,
            Persona persona,
            SuggestionLanguage aiLanguageOverride,
            SuggestionTone aiToneOverride,
            AppUser createdByUser,
            Instant now) {
        this(workspace, name, description, autonomyMode, sourcePolicy, sourceAsset, contentSource, selectionPolicy,
                targetSocialAccount, cadenceType, cadenceIntervalHours, scheduleDelayMinutes, maxRunsPerDay,
                aiPolicy, persona, aiLanguageOverride, aiToneOverride, null, createdByUser, now);
    }

    /** Phase 14A: adds the optional Experiment opt-in (item 13) as its own trailing axis. */
    public Robot(
            Workspace workspace,
            String name,
            String description,
            RobotAutonomyMode autonomyMode,
            RobotSourcePolicy sourcePolicy,
            MediaAsset sourceAsset,
            ContentSource contentSource,
            RobotSelectionPolicy selectionPolicy,
            SocialAccount targetSocialAccount,
            RobotCadenceType cadenceType,
            Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes,
            int maxRunsPerDay,
            RobotAiPolicy aiPolicy,
            Persona persona,
            SuggestionLanguage aiLanguageOverride,
            SuggestionTone aiToneOverride,
            UUID experimentId,
            AppUser createdByUser,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.name = name;
        this.description = description;
        this.status = RobotStatus.ACTIVE;
        this.autonomyMode = autonomyMode;
        this.highlightStrategy = RobotHighlightStrategy.TOP_HIGHLIGHT;
        this.sourcePolicy = sourcePolicy;
        this.sourceAsset = sourceAsset;
        this.contentSource = contentSource;
        this.selectionPolicy = selectionPolicy;
        this.targetSocialAccount = targetSocialAccount;
        this.cadenceType = cadenceType;
        this.cadenceIntervalHours = cadenceIntervalHours;
        this.scheduleDelayMinutes = scheduleDelayMinutes;
        this.maxRunsPerDay = maxRunsPerDay;
        this.aiPolicy = aiPolicy;
        this.persona = persona;
        this.aiLanguageOverride = aiLanguageOverride;
        this.aiToneOverride = aiToneOverride;
        this.experimentId = experimentId;
        this.createdByUser = createdByUser;
        this.createdAt = now;
        this.updatedAt = now;
        if (cadenceType == RobotCadenceType.INTERVAL) {
            this.nextRunAt = now.plusSeconds(cadenceIntervalHours * 3600L);
        }
    }

    @PrePersist
    void prePersist() {
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = timestamp;
        }
        if (updatedAt == null) {
            updatedAt = timestamp;
        }
    }

    public void update(
            String name,
            String description,
            RobotAutonomyMode autonomyMode,
            SocialAccount targetSocialAccount,
            RobotCadenceType cadenceType,
            Integer cadenceIntervalHours,
            Integer scheduleDelayMinutes,
            int maxRunsPerDay,
            RobotAiPolicy aiPolicy,
            Persona persona,
            SuggestionLanguage aiLanguageOverride,
            SuggestionTone aiToneOverride,
            UUID experimentId,
            Instant now) {
        this.name = name;
        this.description = description;
        this.autonomyMode = autonomyMode;
        this.targetSocialAccount = targetSocialAccount;
        this.maxRunsPerDay = maxRunsPerDay;
        this.scheduleDelayMinutes = scheduleDelayMinutes;
        this.aiPolicy = aiPolicy;
        this.persona = persona;
        this.aiLanguageOverride = aiLanguageOverride;
        this.aiToneOverride = aiToneOverride;
        this.experimentId = experimentId;
        boolean cadenceChanged = this.cadenceType != cadenceType
                || !java.util.Objects.equals(this.cadenceIntervalHours, cadenceIntervalHours);
        this.cadenceType = cadenceType;
        this.cadenceIntervalHours = cadenceIntervalHours;
        if (cadenceType == RobotCadenceType.INTERVAL && (cadenceChanged || nextRunAt == null)) {
            this.nextRunAt = now.plusSeconds(cadenceIntervalHours * 3600L);
        } else if (cadenceType == RobotCadenceType.MANUAL_ONLY) {
            this.nextRunAt = null;
        }
        this.updatedAt = now;
    }

    public void pause(Instant now) {
        this.status = RobotStatus.PAUSED;
        this.updatedAt = now;
    }

    public void resume(Instant now) {
        this.status = RobotStatus.ACTIVE;
        if (cadenceType == RobotCadenceType.INTERVAL && nextRunAt != null && nextRunAt.isBefore(now)) {
            // A long pause must not immediately fire a backlog of catch-up
            // runs the moment the Robot resumes; re-anchor to one interval
            // from now instead of leaving a far-past nextRunAt for the
            // scheduler to treat as merely "one" catch-up (see misfire notes
            // on the scheduler itself, which only applies to genuine server
            // downtime, not an intentional pause).
            this.nextRunAt = now.plusSeconds(cadenceIntervalHours * 3600L);
        }
        this.updatedAt = now;
    }

    /** Called by the scheduler immediately after claiming this Robot for a due run. */
    public void advanceNextRunAt(Instant now) {
        if (cadenceType != RobotCadenceType.INTERVAL) {
            throw new IllegalStateException("Only an INTERVAL robot has nextRunAt to advance");
        }
        this.lastRunAt = now;
        this.nextRunAt = now.plusSeconds(cadenceIntervalHours * 3600L);
        this.updatedAt = now;
    }

    public void recordManualRun(Instant now) {
        this.lastRunAt = now;
        this.updatedAt = now;
    }

    /** Phase 15A: the sole operational mutation performed by a decision application. */
    public void applyPersona(Persona persona, Instant now) {
        this.persona = persona;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public RobotStatus getStatus() { return status; }
    public RobotAutonomyMode getAutonomyMode() { return autonomyMode; }
    public RobotHighlightStrategy getHighlightStrategy() { return highlightStrategy; }
    public RobotSourcePolicy getSourcePolicy() { return sourcePolicy; }
    public MediaAsset getSourceAsset() { return sourceAsset; }
    public ContentSource getContentSource() { return contentSource; }
    public RobotSelectionPolicy getSelectionPolicy() { return selectionPolicy; }
    public SocialAccount getTargetSocialAccount() { return targetSocialAccount; }
    public RobotCadenceType getCadenceType() { return cadenceType; }
    public Integer getCadenceIntervalHours() { return cadenceIntervalHours; }
    public Integer getScheduleDelayMinutes() { return scheduleDelayMinutes; }
    public int getMaxRunsPerDay() { return maxRunsPerDay; }
    public RobotAiPolicy getAiPolicy() { return aiPolicy; }
    public Persona getPersona() { return persona; }
    public SuggestionLanguage getAiLanguageOverride() { return aiLanguageOverride; }
    public SuggestionTone getAiToneOverride() { return aiToneOverride; }
    public UUID getExperimentId() { return experimentId; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
