package com.fdmultimedia.api.robotchanges;

import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.AttemptResult;
import com.fdmultimedia.api.robotchanges.AdaptiveExecutionModels.AttemptTrigger;
import com.fdmultimedia.api.workspaces.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Append-only, state-transition-deduplicated audit of automatic execution evaluations. */
@Entity
@Table(name = "adaptive_execution_attempts")
public class AdaptiveExecutionAttempt {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id") private Workspace workspace;
    @Column(name = "authorization_id", nullable = false) private UUID authorizationId;
    @Column(name = "proposal_id", nullable = false) private UUID proposalId;
    @Column(name = "robot_id", nullable = false) private UUID robotId;
    @Column(name = "engine_version", nullable = false) private String engineVersion;
    @Enumerated(EnumType.STRING) @Column(name = "trigger_type", nullable = false) private AttemptTrigger trigger;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AttemptResult result;
    @Column(name = "reason_codes", nullable = false) private String reasonCodes;
    @Column(name = "guardrail_evaluation_id") private UUID guardrailEvaluationId;
    @Column(name = "revision_id") private UUID revisionId;
    @Column(name = "state_fingerprint", nullable = false) private String stateFingerprint;
    @Column(name = "attempted_at", nullable = false) private Instant attemptedAt;

    protected AdaptiveExecutionAttempt() {}

    public AdaptiveExecutionAttempt(Workspace workspace, UUID authorizationId, UUID proposalId, UUID robotId,
            AttemptTrigger trigger, AttemptResult result, String reasonCodes, UUID guardrailEvaluationId, UUID revisionId,
            String stateFingerprint, Instant now) {
        this.id = UUID.randomUUID(); this.workspace = workspace; this.authorizationId = authorizationId;
        this.proposalId = proposalId; this.robotId = robotId; this.engineVersion = AdaptiveExecutionExecutor.ENGINE_VERSION;
        this.trigger = trigger; this.result = result; this.reasonCodes = reasonCodes;
        this.guardrailEvaluationId = guardrailEvaluationId; this.revisionId = revisionId;
        this.stateFingerprint = stateFingerprint; this.attemptedAt = now;
    }

    public UUID getId() { return id; } public UUID getAuthorizationId() { return authorizationId; }
    public AttemptTrigger getTrigger() { return trigger; } public AttemptResult getResult() { return result; }
    public String getReasonCodes() { return reasonCodes; } public UUID getGuardrailEvaluationId() { return guardrailEvaluationId; }
    public UUID getRevisionId() { return revisionId; } public String getStateFingerprint() { return stateFingerprint; }
    public Instant getAttemptedAt() { return attemptedAt; }
}
