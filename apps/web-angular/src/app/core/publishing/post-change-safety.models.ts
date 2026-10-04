import { DashboardMetric } from './publication-dashboard.models';

export type SafetyWindow = 'H24' | 'H72' | 'D7';
export type SafetyMonitorStatus = 'MONITORING' | 'COMPLETED' | 'SUPERSEDED';
export type SafetyEvaluationStatus = 'SUPERSEDED' | 'BASELINE_UNAVAILABLE' | 'TOO_YOUNG' | 'METRIC_UNAVAILABLE' |
  'INSUFFICIENT_SAMPLE' | 'LOW_COVERAGE' | 'NOT_COMPARABLE' | 'READY_STABLE' | 'READY_REGRESSION_OBSERVED';
export type RollbackRecommendationStatus = 'OPEN' | 'ACKNOWLEDGED' | 'DISMISSED' | 'ROLLED_BACK' | 'SUPERSEDED';
export type SafetyExecutionOrigin = 'HUMAN_APPLY' | 'PREAUTHORIZED_AUTO_APPLY';

export interface PostChangeSafetyEvaluation {
  id: string; monitorId: string; robotId: string; revisionId: string; evaluationRevision: number; engineVersion: string;
  window: SafetyWindow; informational: boolean; status: SafetyEvaluationStatus; reasons: string[];
  metric: DashboardMetric | null; provider: string | null; executionOrigin: SafetyExecutionOrigin; authorizationId: string | null;
  baselineSample: number | null; baselineCoverage: number | null; baselineValue: number | null;
  epochStart: string; epochEnd: string | null; postRuns: number; postPublished: number; postEligible: number; postSample: number;
  postCoverage: number | null; postValue: number | null; absoluteDifference: number | null; relativeDifferencePercent: number | null;
  materialThresholdPercent: number; minSample: number; minCoverage: number; evaluatedAt: string;
}

export interface PostChangeSafetyBaseline {
  id: string; window: SafetyWindow; metric: DashboardMetric; provider: string; sample: number; eligibleCoverage: number; mean: number;
}

export interface RollbackRecommendation {
  id: string; robotId: string; revisionId: string; robotRevision: number; evaluationId: string; window: SafetyWindow;
  status: RollbackRecommendationStatus; metric: DashboardMetric; provider: string;
  previousPersonaName: string | null; currentPersonaName: string; executionOrigin: SafetyExecutionOrigin; authorizationId: string | null;
  baselineSample: number; baselineValue: number; postSample: number; postCoverage: number; postValue: number;
  absoluteDifference: number; relativeDifferencePercent: number; reason: string; limitations: string;
  createdAt: string; acknowledgedAt: string | null; dismissedAt: string | null; resolvedAt: string | null; rollbackRevisionId: string | null;
}

export interface RevisionSafety {
  revisionId: string; robotRevision: number; executionOrigin: SafetyExecutionOrigin; authorizationId: string | null;
  monitorId: string | null; monitorStatus: SafetyMonitorStatus | null; metric: DashboardMetric | null; provider: string | null;
  epochStart: string; epochEnd: string | null; latestEvaluations: PostChangeSafetyEvaluation[];
  baselines: PostChangeSafetyBaseline[]; recommendation: RollbackRecommendation | null; disclaimer: string;
}
