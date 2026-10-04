import { DashboardMetric } from './publication-dashboard.models';
import { CampaignWindow } from './campaign-performance.models';

export type RobotChangeProposalStatus =
  | 'READY_FOR_REVIEW' | 'APPROVED' | 'APPLIED' | 'REJECTED' | 'STALE' | 'ROLLED_BACK' | 'FAILED';

export interface RobotChangeEligibility {
  eligible: boolean;
  reasonCode: string | null;
  sourceOptimizationProposalId: string;
  targetRobotId: string;
}

export interface RobotChangeProposal {
  id: string;
  sourceOptimizationProposalId: string;
  sourceExperimentId: string;
  engineVersion: string;
  factor: 'PERSONA';
  status: RobotChangeProposalStatus;
  targetRobotId: string;
  targetRobotNameSnapshot: string;
  currentPersonaId: string | null;
  currentPersonaNameSnapshot: string | null;
  proposedPersonaId: string;
  proposedPersonaNameSnapshot: string;
  expectedRobotConfigFingerprint: string;
  analysisEngineVersion: string;
  metric: DashboardMetric;
  observationWindow: CampaignWindow;
  population: 'ASSIGNED_OBSERVED';
  baselineSampleCount: number;
  candidateSampleCount: number;
  baselineCoverage: number | null;
  candidateCoverage: number | null;
  absoluteMeanDifference: number;
  relativeMeanDifferencePercent: number | null;
  standardError: number | null;
  degreesOfFreedom: number | null;
  confidenceIntervalLower: number | null;
  confidenceIntervalUpper: number | null;
  confidenceIntervalIncludesZero: boolean | null;
  pValue: number | null;
  standardizedEffectSize: number | null;
  limitations: string[];
  rationale: string;
  proposalFingerprint: string;
  createdAt: string;
  reviewedAt: string | null;
  appliedAt: string | null;
  rolledBackAt: string | null;
}

export type RobotConfigurationChangeType = 'PERSONA_CHANGE' | 'ROLLBACK';

export interface RobotConfigurationRevision {
  id: string;
  robotId: string;
  revision: number;
  changeType: RobotConfigurationChangeType;
  previousPersonaId: string | null;
  previousPersonaNameSnapshot: string | null;
  newPersonaId: string | null;
  newPersonaNameSnapshot: string | null;
  previousConfigFingerprint: string;
  newConfigFingerprint: string;
  sourceProposalId: string | null;
  sourceExperimentId: string | null;
  rollbackOfRevisionId: string | null;
  reason: string | null;
  createdAt: string;
  guardrailEvaluationId: string | null;
  adaptivePolicyRevision: number | null;
  guardrailEngineVersion: string | null;
  executionOrigin: 'HUMAN_APPLY' | 'PREAUTHORIZED_AUTO_APPLY' | 'HUMAN_ROLLBACK';
  executionAuthorizationId: string | null;
  executionEngineVersion: string | null;
}

export type AdaptiveGuardrailReason = 'POLICY_DISABLED' | 'CHANGE_BUDGET_EXHAUSTED' | 'COOLDOWN_ACTIVE' |
  'ACTIVE_EXPERIMENT' | 'PENDING_CHANGE_EXISTS' | 'POST_CHANGE_OBSERVATION_REQUIRED';

export interface AdaptiveGuardrailEvaluation {
  id: string; proposalId: string; robotId: string; engineVersion: string; trigger: 'CREATE' | 'APPROVE' | 'APPLY' | 'CHECK';
  policyRevision: number; eligible: boolean; reasons: AdaptiveGuardrailReason[];
  budgetAllowed: number; budgetUsed: number; budgetRemaining: number; budgetWindowDays: number;
  latestConfigurationRevisionId: string | null; lastConfigurationChangeAt: string | null;
  cooldownHours: number; cooldownEndsAt: string | null; activeExperimentId: string | null; pendingProposalCount: number;
  runsSinceRevision: number; publicationsSinceRevision: number; eligibleByAgeCount: number;
  analyticsPublicationCount: number; metricSampleCount: number; coverage: number | null;
  requiredSampleCount: number; requiredCoverage: number; observationWindow: 'H72'; evaluatedAt: string;
}

export interface RobotAdaptivePolicy {
  robotId: string; revision: number; persisted: boolean; enabled: boolean;
  maxAppliedChangesPerWindow: number; changeBudgetWindowDays: number; cooldownHours: number;
  requireNoActiveExperiment: boolean; requireNoPendingChange: boolean; requirePostChangeObservation: boolean;
  proposalAutomationMode: 'MANUAL_ONLY' | 'AUTO_PROPOSE';
  updatedAt: string | null;
}

export interface AutonomousProposalEligibility {
  robotId: string; eligible: boolean; reasons: string[]; policyRevision: number;
  sourceReviewId: string | null; candidateCountConsidered: number;
  selectedCandidatePersonaId: string | null; evidenceFingerprint: string | null;
  opportunityFingerprint: string | null; existingProposalId: string | null;
  memorySkippedCandidates?: { candidatePersonaId: string; reasons: string[]; suppressionUntil: string | null; latestOutcome: string | null }[];
}

export interface RobotAdaptivePolicyRevision {
  id: string; robotId: string; revision: number; previousValues: string | null; newValues: string;
  actorUserId: string; createdAt: string;
}

export type ExecutionAuthorizationStatus = 'ACTIVE' | 'CONSUMED' | 'REVOKED' | 'EXPIRED' | 'INVALIDATED';

export interface ExecutionAuthorization {
  id: string; proposalId: string; robotId: string; robotName: string; factor: 'PERSONA';
  fromPersonaId: string | null; fromPersonaName: string | null; toPersonaId: string; toPersonaName: string;
  sourceExperimentId: string; maxExecutions: number; status: ExecutionAuthorizationStatus; terminalReason: string | null;
  validFrom: string; expiresAt: string; policyRevision: number; executionEngineVersion: string;
  createdByUserId: string | null; createdAt: string; terminatedAt: string | null;
  consumedRevisionId: string | null; consumedGuardrailEvaluationId: string | null;
}

export interface ExecutionAuthorizationEligibility {
  authorizationId: string; status: ExecutionAuthorizationStatus; proposalStatus: RobotChangeProposalStatus;
  expiresAt: string; eligibleNow: boolean; reasons: string[]; guardrailReasons: string[];
  robotFingerprintMatch: boolean; targetPersonaActive: boolean; evaluatedAt: string;
}