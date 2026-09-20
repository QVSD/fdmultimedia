import { DashboardDimension, DashboardFilters, DashboardMetric } from './publication-dashboard.models';

export type InsightStatistic = 'AVERAGE' | 'MEDIAN';
export type InsightDirection = 'HIGHER_OBSERVED' | 'LOWER_OBSERVED' | 'SIMILAR_OBSERVED';
export type InsightResultType = 'DIRECTIONAL_COMPARISON' | 'INSUFFICIENT_SAMPLE' | 'LOW_COVERAGE' | 'TOO_YOUNG' | 'METRIC_UNAVAILABLE';
export type RecommendationType = 'COLLECT_MORE_DATA' | 'WAIT_FOR_OBSERVATION_WINDOW' | 'REVIEW_CONTENT_DIFFERENCES' | 'CHECK_ANALYTICS_COVERAGE';

export interface InsightRecommendation {
  type: RecommendationType;
  message: string;
}

export interface SegmentEvidence {
  segmentId: string;
  label: string;
  publicationCount: number;
  eligibleByAgeCount: number;
  analyticsPublicationCount: number;
  sampleCount: number;
  coverage: number | null;
  medianValue: number | null;
  averageValue: number | null;
}

export interface ComparisonResult {
  id: string;
  type: InsightResultType;
  engineVersion: string;
  dimension: DashboardDimension;
  metric: DashboardMetric;
  statistic: InsightStatistic;
  observationWindow: string;
  left: SegmentEvidence;
  right: SegmentEvidence;
  absoluteDifference: number | null;
  relativeDifferencePercent: number | null;
  direction: InsightDirection | null;
  materialDifference: boolean;
  message: string;
  limitations: string[];
  recommendations: InsightRecommendation[];
}

export interface InsightNotice {
  type: string;
  message: string;
}

export interface InsightsResponse {
  engineVersion: string;
  filtersFingerprint: string;
  disclaimer: string;
  notices: InsightNotice[];
  observations: ComparisonResult[];
}

export interface CompareRequest extends DashboardFilters {
  dimension: DashboardDimension;
  leftSegmentId: string;
  rightSegmentId: string;
  metric: DashboardMetric;
  statistic?: InsightStatistic;
}
