package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.robotchanges.RobotChangeProposalModels.ChangeType;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, append-only history of every Robot PERSONA configuration
 * change applied through Phase 17I — never updated or deleted after
 * insertion. Every successful Apply or Rollback creates exactly one new row
 * with a monotonically increasing {@code revision} per Robot (DB-enforced via
 * a unique {@code (robot_id, revision)} constraint); nothing here ever
 * rewrites or removes an earlier row, including rollback, which always adds
 * a new row restoring the prior Persona rather than mutating history.
 */
@Entity
@Table(name = "robot_configuration_revisions")
public class RobotConfigurationRevision {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id") private Workspace workspace;
    @Column(name = "robot_id", nullable = false) private UUID robotId;
    @Column(nullable = false) private int revision;
    @Enumerated(EnumType.STRING) @Column(name = "change_type", nullable = false) private ChangeType changeType;
    @Column(name = "previous_persona_id") private UUID previousPersonaId;
    @Column(name = "previous_persona_name_snapshot") private String previousPersonaNameSnapshot;
    @Column(name = "new_persona_id") private UUID newPersonaId;
    @Column(name = "new_persona_name_snapshot") private String newPersonaNameSnapshot;
    @Column(name = "previous_config_fingerprint", nullable = false) private String previousConfigFingerprint;
    @Column(name = "new_config_fingerprint", nullable = false) private String newConfigFingerprint;
    @Column(name = "source_proposal_id") private UUID sourceProposalId;
    @Column(name = "source_experiment_id") private UUID sourceExperimentId;
    @Column(name = "rollback_of_revision_id") private UUID rollbackOfRevisionId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "actor_user_id") private AppUser actor;
    @Column private String reason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected RobotConfigurationRevision() {}

    public RobotConfigurationRevision(Workspace workspace, UUID robotId, int revision, ChangeType changeType,
            UUID previousPersonaId, String previousPersonaNameSnapshot, UUID newPersonaId, String newPersonaNameSnapshot,
            String previousConfigFingerprint, String newConfigFingerprint, UUID sourceProposalId, UUID sourceExperimentId,
            UUID rollbackOfRevisionId, AppUser actor, String reason, Instant now) {
        this.id = UUID.randomUUID(); this.workspace = workspace; this.robotId = robotId; this.revision = revision;
        this.changeType = changeType; this.previousPersonaId = previousPersonaId;
        this.previousPersonaNameSnapshot = previousPersonaNameSnapshot; this.newPersonaId = newPersonaId;
        this.newPersonaNameSnapshot = newPersonaNameSnapshot; this.previousConfigFingerprint = previousConfigFingerprint;
        this.newConfigFingerprint = newConfigFingerprint; this.sourceProposalId = sourceProposalId;
        this.sourceExperimentId = sourceExperimentId; this.rollbackOfRevisionId = rollbackOfRevisionId;
        this.actor = actor; this.reason = reason; this.createdAt = now;
    }

    public UUID getId() { return id; } public Workspace getWorkspace() { return workspace; } public UUID getRobotId() { return robotId; }
    public int getRevision() { return revision; } public ChangeType getChangeType() { return changeType; }
    public UUID getPreviousPersonaId() { return previousPersonaId; } public String getPreviousPersonaNameSnapshot() { return previousPersonaNameSnapshot; }
    public UUID getNewPersonaId() { return newPersonaId; } public String getNewPersonaNameSnapshot() { return newPersonaNameSnapshot; }
    public String getPreviousConfigFingerprint() { return previousConfigFingerprint; } public String getNewConfigFingerprint() { return newConfigFingerprint; }
    public UUID getSourceProposalId() { return sourceProposalId; } public UUID getSourceExperimentId() { return sourceExperimentId; }
    public UUID getRollbackOfRevisionId() { return rollbackOfRevisionId; } public AppUser getActor() { return actor; }
    public String getReason() { return reason; } public Instant getCreatedAt() { return createdAt; }
}
