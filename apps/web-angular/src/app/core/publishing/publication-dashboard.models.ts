export type DashboardWindow = 'LATEST' | 'H24' | 'H72' | 'D7';
export type DashboardMetric = 'VIEWS' | 'REACH' | 'LIKES' | 'COMMENTS' | 'SHARES' | 'SAVES' | 'TOTAL_INTERACTIONS';
export type DashboardDimension = 'ROBOT' | 'PERSONA' | 'CONTENT_SOURCE' | 'PROVIDER' | 'ORIGIN' | 'AI_USAGE';

export interface DashboardFilters {
  dateFrom?: string;
  dateTo?: string;
  window?: DashboardWindow;
  provider?: string;
  robotId?: string;
  personaId?: string;
  contentSourceId?: string;
  origin?: 'MANUAL' | 'ROBOT';
  aiUsage?: 'AI_APPLIED' | 'NO_APPLIED_AI';
}

export interface DashboardCoverage {
  publicationCount: number;
  analyticsPublicationCount: number;
  eligibleByAgeCount: number;
  tooYoungCount: number;
  missingSnapshotCount: number;
}

export interface DashboardAggregate {
  total: number | null;
  average: number | null;
  median: number | null;
  sampleCount: number;
}

export interface DashboardSummary {
  filters: DashboardFilters;
  coverage: DashboardCoverage;
  metrics: Record<DashboardMetric, DashboardAggregate>;
}

export interface DashboardTrend {
  filters: DashboardFilters;
  metric: DashboardMetric;
  points: { date: string; coverage: DashboardCoverage; metric: DashboardAggregate }[];
}

export interface DashboardBreakdown {
  filters: DashboardFilters;
  dimension: DashboardDimension;
  rows: { key: string; label: string; coverage: DashboardCoverage; metrics: Record<DashboardMetric, DashboardAggregate> }[];
  truncated: boolean;
}

export interface DashboardOptions {
  providers: string[];
  robots: { id: string; label: string }[];
  personas: { id: string; label: string }[];
  contentSources: { id: string; label: string }[];
  truncated: boolean;
}
