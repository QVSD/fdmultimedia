package com.fdmultimedia.api.optimization;

import java.util.List;
import java.util.UUID;

public final class AutonomousProposalModels {
    private AutonomousProposalModels() {}
    public enum Reason {
        MANUAL_ONLY,
        ADAPTIVE_POLICY_DISABLED,
        NO_BASELINE_PERSONA,
        ACTIVE_EXPERIMENT,
        PENDING_OPTIMIZATION_PROPOSAL,
        PENDING_ROBOT_CHANGE_PROPOSAL,
        NO_ELIGIBLE_REVIEW,
        STALE_EVIDENCE,
        POST_CHANGE_OBSERVATION_REQUIRED,
        NO_ELIGIBLE_CANDIDATE,
        DUPLICATE_OPPORTUNITY
    }
    public record Evaluation(UUID robotId,boolean eligible,List<Reason> reasons,int policyRevision,
            UUID sourceReviewId,int candidateCountConsidered,UUID selectedCandidatePersonaId,
            String evidenceFingerprint,String opportunityFingerprint,UUID existingProposalId) {}
}
