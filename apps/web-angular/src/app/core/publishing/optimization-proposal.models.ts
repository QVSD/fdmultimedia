import { DashboardMetric } from './publication-dashboard.models';
import { CampaignWindow } from './campaign-performance.models';

export type OptimizationProposalStatus = 'READY_FOR_REVIEW' | 'APPROVED' | 'REJECTED' | 'MATERIALIZED' | 'STALE' | 'FAILED';
export interface OptimizationEligibility {
  eligible: boolean; reasonCode: string | null; baselinePersonaId: string | null; baselinePersonaName: string | null;
  metric: DashboardMetric; observationWindow: CampaignWindow; provider: string | null;
  minimumSample: number; minimumCoverage: number; materialDifferencePercent: number; limitations: string[];
}
export interface OptimizationProposal {
  id: string; sourceReviewId: string; revision: number; current: boolean; engineVersion: string; factor: 'PERSONA';
  status: OptimizationProposalStatus; baselinePersonaId: string; baselinePersonaName: string;
  candidatePersonaId: string; candidatePersonaName: string; metric: DashboardMetric; statistic: 'MEDIAN';
  observationWindow: CampaignWindow; provider: string; cohortFrom: string; cohortTo: string;
  baselineSample: number; candidateSample: number; baselineEligible: number; candidateEligible: number;
  baselineCoverage: number; candidateCoverage: number; baselineValue: number; candidateValue: number;
  absoluteDifference: number; relativeDifferencePercent: number | null;
  direction: 'HIGHER_OBSERVED' | 'LOWER_OBSERVED' | 'SIMILAR_OBSERVED'; evidenceFingerprint: string;
  rationale: string; limitation: string; materializedExperimentId: string | null;
  createdAt: string; reviewedAt: string | null; materializedAt: string | null;
}
