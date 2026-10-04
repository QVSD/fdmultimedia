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
        DUPLICATE_OPPORTUNITY,
        /** Phase 17N: every otherwise-valid candidate in the bounded canonical scan was suppressed by adaptive memory. */
        ALL_CANDIDATES_MEMORY_SUPPRESSED
    }
    /** A candidate that passed the canonical 17H gate but was skipped by Phase 17N memory screening (it never creates a proposal). */
    public record MemorySkip(UUID candidatePersonaId,List<String> reasons,java.time.Instant suppressionUntil,String latestOutcome) {}
    public record Evaluation(UUID robotId,boolean eligible,List<Reason> reasons,int policyRevision,
            UUID sourceReviewId,int candidateCountConsidered,UUID selectedCandidatePersonaId,
            String evidenceFingerprint,String opportunityFingerprint,UUID existingProposalId,
            List<MemorySkip> memorySkippedCandidates) {
        public Evaluation(UUID robotId,boolean eligible,List<Reason> reasons,int policyRevision,
                UUID sourceReviewId,int candidateCountConsidered,UUID selectedCandidatePersonaId,
                String evidenceFingerprint,String opportunityFingerprint,UUID existingProposalId){
            this(robotId,eligible,reasons,policyRevision,sourceReviewId,candidateCountConsidered,selectedCandidatePersonaId,
                    evidenceFingerprint,opportunityFingerprint,existingProposalId,List.of());
        }
    }
}
