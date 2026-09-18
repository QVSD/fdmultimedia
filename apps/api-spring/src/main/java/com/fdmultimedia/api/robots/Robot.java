package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.assets.MediaAsset;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_asset_id", nullable = false)
    private MediaAsset sourceAsset;

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
        this.id = UUID.randomUUID();
        this.workspace = workspace;
        this.name = name;
        this.description = description;
        this.status = RobotStatus.ACTIVE;
        this.autonomyMode = autonomyMode;
        this.highlightStrategy = RobotHighlightStrategy.TOP_HIGHLIGHT;
        this.sourceAsset = sourceAsset;
        this.targetSocialAccount = targetSocialAccount;
        this.cadenceType = cadenceType;
        this.cadenceIntervalHours = cadenceIntervalHours;
        this.scheduleDelayMinutes = scheduleDelayMinutes;
        this.maxRunsPerDay = maxRunsPerDay;
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
            Instant now) {
        this.name = name;
        this.description = description;
        this.autonomyMode = autonomyMode;
        this.targetSocialAccount = targetSocialAccount;
        this.maxRunsPerDay = maxRunsPerDay;
        this.scheduleDelayMinutes = scheduleDelayMinutes;
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

    public UUID getId() { return id; }
    public Workspace getWorkspace() { return workspace; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public RobotStatus getStatus() { return status; }
    public RobotAutonomyMode getAutonomyMode() { return autonomyMode; }
    public RobotHighlightStrategy getHighlightStrategy() { return highlightStrategy; }
    public MediaAsset getSourceAsset() { return sourceAsset; }
    public SocialAccount getTargetSocialAccount() { return targetSocialAccount; }
    public RobotCadenceType getCadenceType() { return cadenceType; }
    public Integer getCadenceIntervalHours() { return cadenceIntervalHours; }
    public Integer getScheduleDelayMinutes() { return scheduleDelayMinutes; }
    public int getMaxRunsPerDay() { return maxRunsPerDay; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public AppUser getCreatedByUser() { return createdByUser; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
