export type AdaptiveLifecycleState =
  | 'MANUAL_ONLY' | 'WAITING_FOR_EVIDENCE' | 'OPPORTUNITY_AVAILABLE' | 'PROPOSAL_AWAITING_REVIEW'
  | 'PROPOSAL_APPROVED' | 'WAITING_FOR_AUTHORIZATION' | 'AUTHORIZED_WAITING_FOR_GUARDRAILS'
  | 'READY_FOR_APPLY' | 'OBSERVING' | 'STABLE' | 'ROLLBACK_REVIEW_RECOMMENDED'
  | 'MEMORY_SUPPRESSED' | 'BLOCKED';

export type AdaptiveNextAction =
  | 'NONE' | 'COLLECT_EVIDENCE' | 'REVIEW_PROPOSAL' | 'MATERIALIZE_EXPERIMENT'
  | 'AUTHORIZE_EXECUTION' | 'WAIT_FOR_GUARDRAILS' | 'APPLY_CHANGE' | 'WAIT_FOR_OBSERVATION'
  | 'REVIEW_ROLLBACK' | 'RECONSIDER_AFTER_SUPPRESSION';

export interface AdaptiveLifecycleEvidence {
  sourceReviewId: string | null; metric: string | null; window: string | null;
  sample: number | null; coverage: number | null; status: string;
}

export interface AdaptiveLifecycleMemory {
  candidatePersonaId: string; reasons: string[]; suppressionUntil: string | null; latestOutcome: string | null;
}

export interface RobotAdaptiveLifecycle {
  workspaceId: string; robotId: string; state: AdaptiveLifecycleState; engineVersion: 'ADAPTIVE_LIFECYCLE_V1';
  computedAt: string; configurationRevisionId: string | null; configurationRevisionNumber: number | null;
  currentPersonaId: string | null; policyMode: 'MANUAL_ONLY' | 'AUTO_PROPOSE'; policyRevision: number;
  optimizationProposalId: string | null; optimizationProposalStatus: string | null; proposalOrigin: string | null;
  robotChangeProposalId: string | null; robotChangeProposalStatus: string | null;
  authorizationId: string | null; authorizationStatus: string | null; guardrailEligible: boolean | null;
  guardrailReasons: string[]; monitorId: string | null; safetyStatus: string | null;
  recommendationId: string | null; recommendationStatus: string | null; memory: AdaptiveLifecycleMemory[];
  nextAction: AdaptiveNextAction; humanActionRequired: boolean; automaticActionPossible: boolean;
  reasonCodes: string[]; evidenceSummary: AdaptiveLifecycleEvidence;
}
