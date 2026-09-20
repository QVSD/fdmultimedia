export type ExperimentFactor = 'PERSONA';
export type ExperimentStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
export type AssignmentStrategy = 'DETERMINISTIC_BALANCED_V1';
export type ExperimentVariantKey = 'A' | 'B';
/** LATEST is deliberately not a valid option here — see docs/ARCHITECTURE.md. */
export type ExperimentObservationWindow = 'H24' | 'H72' | 'D7';
export type ExperimentMetric = 'VIEWS' | 'REACH' | 'LIKES' | 'COMMENTS' | 'SHARES' | 'SAVES' | 'TOTAL_INTERACTIONS';

export interface ExperimentVariantSummary {
  id: string;
  variantKey: ExperimentVariantKey;
  label: string;
  personaId: string;
  personaNameSnapshot: string | null;
  frozen: boolean;
  assignedCount: number;
}

export interface ExperimentSummary {
  id: string;
  name: string;
  description: string | null;
  hypothesis: string;
  factor: ExperimentFactor;
  status: ExperimentStatus;
  assignmentStrategy: AssignmentStrategy;
  targetObservationWindow: ExperimentObservationWindow;
  primaryMetric: ExperimentMetric;
  variants: ExperimentVariantSummary[];
  createdAt: string;
  updatedAt: string;
  activatedAt: string | null;
  stoppedAt: string | null;
}

export interface ExperimentAssignmentSummary {
  id: string;
  experimentId: string;
  experimentVariantId: string;
  variantKey: ExperimentVariantKey;
  robotRunId: string;
  assignedAt: string;
  assignmentStrategy: AssignmentStrategy;
  factor: ExperimentFactor;
  factorValueNameSnapshot: string;
}

export interface ExperimentOutcomeVariant {
  variantId: string;
  variantKey: ExperimentVariantKey;
  label: string;
  assignedRuns: number;
  failedRuns: number;
  runsWithDraft: number;
  publishedCount: number;
  eligibleByAgeCount: number;
  analyticsPublicationCount: number;
  metricSampleCount: number;
  coverage: string | null;
  average: string | null;
  median: string | null;
}

export interface ExperimentOutcome {
  experimentId: string;
  targetObservationWindow: ExperimentObservationWindow;
  primaryMetric: ExperimentMetric;
  disclaimer: string;
  notices: string[];
  variants: ExperimentOutcomeVariant[];
}

// ---- Phase 14B: statistical analysis ----

export type AnalysisPopulation = 'ASSIGNED_OBSERVED' | 'PER_PROTOCOL_OBSERVED';
/**
 * Never `SUCCESSFUL_VARIANT_A`/`WINNER_A` — only describes whether
 * inferential statistics could be computed, never which variant is
 * "better." Descriptive stats are always present regardless of status.
 */
export type AnalysisStatus = 'NO_OBSERVATIONS' | 'MIXED_PROVIDERS' | 'INSUFFICIENT_SAMPLE' | 'INSUFFICIENT_VARIANCE' | 'READY';

export interface ExperimentVariantAnalysis {
  variantKey: ExperimentVariantKey;
  label: string;
  assignmentCount: number;
  failedRunCount: number;
  publishedCount: number;
  eligibleByAgeCount: number;
  tooYoungCount: number;
  snapshotCount: number;
  metricSampleCount: number;
  protocolDeviationCount: number;
  assignmentOutcomeCoverage: string | null;
  eligibleOutcomeCoverage: string | null;
  mean: string | null;
  median: string | null;
  standardDeviation: string | null;
  min: string | null;
  max: string | null;
}

/** `confidenceIntervalIncludesZero` is purely descriptive — never rendered as winner/loser/ship/do-not-ship. */
export interface ExperimentEffectEstimate {
  absoluteMeanDifference: string | null;
  relativeMeanDifferencePercent: string | null;
  standardError: string | null;
  degreesOfFreedom: string | null;
  confidenceIntervalLower: string | null;
  confidenceIntervalUpper: string | null;
  confidenceIntervalIncludesZero: boolean | null;
  pValue: string | null;
  standardizedEffectSize: string | null;
}

export interface ExperimentPopulationAnalysis {
  population: AnalysisPopulation;
  status: AnalysisStatus;
  variantA: ExperimentVariantAnalysis;
  variantB: ExperimentVariantAnalysis;
  effect: ExperimentEffectEstimate;
}

/** Deliberately has no winner/recommendedVariant/deployVariant field anywhere — by omission, not a runtime check. */
export interface ExperimentAnalysisResponse {
  analysisVersion: string;
  experimentId: string;
  experimentName: string;
  experimentStatus: ExperimentStatus;
  factor: ExperimentFactor;
  targetObservationWindow: ExperimentObservationWindow;
  primaryMetric: ExperimentMetric;
  confidenceLevel: string;
  activeExperimentWarning: string | null;
  limitations: string[];
  assignedObserved: ExperimentPopulationAnalysis;
  perProtocolObserved: ExperimentPopulationAnalysis;
}

export interface CreateExperimentRequest {
  name: string;
  description: string | null;
  hypothesis: string;
  factor: ExperimentFactor;
  targetObservationWindow: ExperimentObservationWindow;
  primaryMetric: ExperimentMetric;
  variantAPersonaId: string;
  variantALabel: string | null;
  variantBPersonaId: string;
  variantBLabel: string | null;
}

export type UpdateExperimentRequest = Omit<CreateExperimentRequest, 'factor'>;
