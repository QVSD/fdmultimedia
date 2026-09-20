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
