package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.AuthorizationStatus;
import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.TerminalReason;
import com.fdmultimedia.api.users.AppUser;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A human-created, single-use, expiring grant allowing exactly one automatic Apply of one exact,
 * already human-approved {@link RobotChangeProposal}. Scope fields are immutable; only lifecycle
 * fields change, and only through the transition methods below.
 */
@Entity
@Table(name = "robot_adaptive_execution_authorizations")
public class RobotAdaptiveExecutionAuthorization {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", updatable = false) private Workspace workspace;
    @Column(name = "proposal_id", nullable = false, updatable = false) private UUID proposalId;
    @Column(name = "robot_id", nullable = false, updatable = false) private UUID robotId;
    @Column(nullable = false, updatable = false) private String factor;
    @Column(name = "from_persona_id", updatable = false) private UUID fromPersonaId;
    @Column(name = "to_persona_id", nullable = false, updatable = false) private UUID toPersonaId;
    @Column(name = "source_experiment_id", nullable = false, updatable = false) private UUID sourceExperimentId;
    @Column(name = "max_executions", nullable = false, updatable = false) private int maxExecutions;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AuthorizationStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "terminal_reason") private TerminalReason terminalReason;
    @Column(name = "valid_from", nullable = false, updatable = false) private Instant validFrom;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "policy_revision", nullable = false, updatable = false) private int policyRevision;
    @Column(name = "guardrail_engine_version", nullable = false, updatable = false) private String guardrailEngineVersion;
    @Column(name = "execution_engine_version", nullable = false, updatable = false) private String executionEngineVersion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id", updatable = false) private AppUser createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "last_evaluated_at") private Instant lastEvaluatedAt;
    @Column(name = "terminated_at") private Instant terminatedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "terminated_by_user_id") private AppUser terminatedBy;
    @Column(name = "consumed_revision_id") private UUID consumedRevisionId;
    @Column(name = "consumed_guardrail_evaluation_id") private UUID consumedGuardrailEvaluationId;

    protected RobotAdaptiveExecutionAuthorization() {}

    public RobotAdaptiveExecutionAuthorization(Workspace workspace, UUID proposalId, UUID robotId, UUID fromPersonaId,
            UUID toPersonaId, UUID sourceExperimentId, Instant validFrom, Instant expiresAt, int policyRevision,
            AppUser createdBy, Instant now) {
        this.id = UUID.randomUUID(); this.workspace = workspace; this.proposalId = proposalId; this.robotId = robotId;
        this.factor = "PERSONA"; this.fromPersonaId = fromPersonaId; this.toPersonaId = toPersonaId;
        this.sourceExperimentId = sourceExperimentId; this.maxExecutions = 1; this.status = AuthorizationStatus.ACTIVE;
        this.validFrom = validFrom; this.expiresAt = expiresAt; this.policyRevision = policyRevision;
        this.guardrailEngineVersion = AdaptiveGuardrailService.ENGINE_VERSION;
        this.executionEngineVersion = AdaptiveExecutionExecutor.ENGINE_VERSION;
        this.createdBy = createdBy; this.createdAt = now;
    }

    public boolean isActive() { return status == AuthorizationStatus.ACTIVE; }
    public boolean isExpiredAt(Instant now) { return !now.isBefore(expiresAt); }
    public void markEvaluated(Instant now) { lastEvaluatedAt = now; }
    public void consume(UUID revisionId, UUID evaluationId, Instant now) {
        requireActive(); status = AuthorizationStatus.CONSUMED; terminalReason = TerminalReason.AUTO_APPLIED;
        terminatedAt = now; consumedRevisionId = revisionId; consumedGuardrailEvaluationId = evaluationId;
    }
    public void revoke(AppUser actor, Instant now) {
        requireActive(); status = AuthorizationStatus.REVOKED; terminalReason = TerminalReason.REVOKED_BY_HUMAN;
        terminatedAt = now; terminatedBy = actor;
    }
    public void expire(Instant now) {
        requireActive(); status = AuthorizationStatus.EXPIRED; terminalReason = TerminalReason.EXPIRED; terminatedAt = now;
    }
    public void invalidate(TerminalReason reason, Instant now) {
        requireActive(); status = AuthorizationStatus.INVALIDATED; terminalReason = reason; terminatedAt = now;
    }
    private void requireActive() { if (status != AuthorizationStatus.ACTIVE) throw new IllegalStateException("Authorization is not ACTIVE"); }

    public UUID getId() { return id; } public Workspace getWorkspace() { return workspace; }
    public UUID getProposalId() { return proposalId; } public UUID getRobotId() { return robotId; }
    public String getFactor() { return factor; } public UUID getFromPersonaId() { return fromPersonaId; }
    public UUID getToPersonaId() { return toPersonaId; } public UUID getSourceExperimentId() { return sourceExperimentId; }
    public int getMaxExecutions() { return maxExecutions; } public AuthorizationStatus getStatus() { return status; }
    public TerminalReason getTerminalReason() { return terminalReason; } public Instant getValidFrom() { return validFrom; }
    public Instant getExpiresAt() { return expiresAt; } public int getPolicyRevision() { return policyRevision; }
    public String getGuardrailEngineVersion() { return guardrailEngineVersion; }
    public String getExecutionEngineVersion() { return executionEngineVersion; } public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; } public Instant getLastEvaluatedAt() { return lastEvaluatedAt; }
    public Instant getTerminatedAt() { return terminatedAt; } public UUID getConsumedRevisionId() { return consumedRevisionId; }
    public UUID getConsumedGuardrailEvaluationId() { return consumedGuardrailEvaluationId; }
}
