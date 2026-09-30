import { DashboardMetric } from './publication-dashboard.models';

export type CampaignWindow = 'H24' | 'H72' | 'D7';
export type CampaignEvidenceStatus = 'TOO_YOUNG' | 'METRIC_UNAVAILABLE' | 'INSUFFICIENT_SAMPLE' | 'LOW_COVERAGE' | 'READY';
export type CampaignDimension = 'ROLE' | 'COORDINATION_POLICY';

export interface CampaignMetricStatistics { total: number | null; average: number | null; median: number | null; minimum: number | null; maximum: number | null; sampleCount: number; }
export interface CampaignOption { robotRunId: string; robotName: string; runStatus: string; startedAt: string; intendedOutputCount: number; actualOutputCount: number; publishedOutputCount: number; latestReviewId: string | null; }
export interface CampaignOutputEvidence {
  id: string; robotRunOutputId: string | null; selectionOrder: number | null; sourceRank: number | null;
  outputStatus: string | null; campaignRole: string | null; highlightCandidateId: string | null;
  campaignPlanId: string | null; campaignPlanRevision: number | null; campaignPlanItemId: string | null;
  campaignCopySetId: string | null; campaignCopySetRevision: number | null; campaignCopyItemId: string | null;
  contentSuggestionId: string | null; contentDraftId: string | null; publishScheduleId: string | null;
  publicationId: string | null; provider: string | null; publishedAt: string | null; analyticsSnapshotId: string | null;
  evidenceStatus: 'UNPUBLISHED' | 'TOO_YOUNG' | 'MISSING_SNAPSHOT' | 'OBSERVED';
  metrics: Record<DashboardMetric, number | null>;
}
export interface CampaignRecommendation { id: string; sequence: number; type: string; metric: DashboardMetric | null; comparedDimension: string | null; evidence: Record<string, unknown>; message: string; limitations: string[]; }
export interface CampaignObservedComparison { leftOutputId: string | null; leftSelectionOrder: number | null; rightOutputId: string | null; rightSelectionOrder: number | null; metric: DashboardMetric; leftValue: number | null; rightValue: number | null; direction: string; message: string; }
export interface CampaignReview {
  id: string; robotRunId: string; revision: number; observationWindow: CampaignWindow; primaryMetric: DashboardMetric;
  engineVersion: string; recommendationEngineVersion: string; evidenceStatus: CampaignEvidenceStatus; robotRunStatus: string;
  intendedOutputCount: number; actualOutputCount: number; publishedOutputCount: number; failedOutputCount: number;
  eligibleByAgeCount: number; analyticsPublicationCount: number; evidenceCutoffAt: string; createdAt: string;
  metrics: Record<DashboardMetric, CampaignMetricStatistics>; outputs: CampaignOutputEvidence[];
  comparisons: CampaignObservedComparison[]; recommendations: CampaignRecommendation[]; limitations: string[];
}
export interface CampaignReviewListItem { id: string; robotRunId: string; revision: number; observationWindow: CampaignWindow; primaryMetric: DashboardMetric; evidenceStatus: CampaignEvidenceStatus; publishedOutputCount: number; analyticsPublicationCount: number; createdAt: string; }
export interface CampaignCohortRow { key: string; label: string; publicationCount: number; eligibleByAgeCount: number; analyticsPublicationCount: number; sampleCount: number; coverage: number | null; metric: CampaignMetricStatistics; }
export interface CampaignCohortComparison { dateFrom: string; dateTo: string; observationWindow: CampaignWindow; metric: DashboardMetric; dimension: CampaignDimension; rows: CampaignCohortRow[]; truncated: boolean; recommendations: CampaignRecommendation[]; limitations: string[]; }
