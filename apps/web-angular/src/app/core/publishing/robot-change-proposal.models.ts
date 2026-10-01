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
}
