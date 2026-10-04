export type MemoryOutcome = 'PROPOSED' | 'HUMAN_REJECTED' | 'APPROVED_NOT_APPLIED' | 'APPLIED' | 'OBSERVED_STABLE' |
  'OBSERVED_REGRESSION' | 'ROLLED_BACK' | 'SUPERSEDED';
export type SuppressionReason = 'RECENTLY_PROPOSED' | 'HUMAN_REJECTED' | 'RECENTLY_APPLIED' | 'OBSERVED_REGRESSION' | 'ROLLED_BACK' | 'CURRENTLY_ACTIVE';

export interface AdaptiveMemoryTransition {
  fromPersonaId: string; fromPersonaName: string | null; toPersonaId: string; toPersonaName: string | null;
  latestOutcome: MemoryOutcome; proposalCount: number; applyCount: number; rollbackCount: number; regressionCount: number;
  firstSeenAt: string; lastSeenAt: string; latestEvidenceAt: string | null; latestSafetyStatus: string | null;
  suppressed: boolean; reasons: SuppressionReason[]; suppressionUntil: string | null;
  latestRevisionId: string | null; latestEvaluationId: string | null; latestRecommendationId: string | null;
}

export interface RobotAdaptiveMemory {
  robotId: string; currentPersonaId: string | null; engineVersion: string; transitions: AdaptiveMemoryTransition[]; totalTransitions: number;
}

export interface AdaptiveMemoryDecision {
  decision: {
    robotId: string; fromPersonaId: string; toPersonaId: string; eligible: boolean; reasons: SuppressionReason[];
    suppressionUntil: string | null; latestOutcome: MemoryOutcome | null; latestEvidenceAt: string | null; engineVersion: string;
  };
  fromPersonaName: string | null; toPersonaName: string | null; warning: boolean;
}
