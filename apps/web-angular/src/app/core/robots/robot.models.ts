import { SuggestionLanguage, SuggestionTone } from '../content-suggestions/content-suggestion.models';

export type RobotStatus = 'ACTIVE' | 'PAUSED' | 'DISABLED';
export type RobotAutonomyMode = 'DRAFT_ONLY' | 'REVIEW_REQUIRED' | 'AUTO_SCHEDULE';
export type RobotHighlightStrategy = 'TOP_HIGHLIGHT';
export type RobotSourcePolicy = 'EXISTING_ASSET' | 'CONTENT_SOURCE';
export type RobotSelectionPolicy = 'OLDEST_UNPROCESSED' | 'NEWEST_UNPROCESSED';
export type RobotCadenceType = 'MANUAL_ONLY' | 'INTERVAL';
export type RobotRunTriggerType = 'MANUAL' | 'SCHEDULED';
/** Independent of autonomyMode (Phase 12C) — never merge the two axes. */
export type RobotAiPolicy = 'NO_AI' | 'GENERATE_FOR_REVIEW' | 'GENERATE_AND_APPLY';
export type RobotRunStatus =
  | 'RUNNING'
  | 'WAITING_FOR_DRAFT'
  | 'WAITING_FOR_AI'
  | 'WAITING_FOR_AI_REVIEW'
  | 'WAITING_FOR_REVIEW'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';
export type RobotApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
export type ExperimentVariantKey = 'A' | 'B';

export interface RobotSummary {
  id: string;
  name: string;
  description: string | null;
  status: RobotStatus;
  autonomyMode: RobotAutonomyMode;
  highlightStrategy: RobotHighlightStrategy;
  sourcePolicy: RobotSourcePolicy;
  sourceAssetId: string | null;
  sourceAssetFilename: string | null;
  contentSourceId: string | null;
  contentSourceName: string | null;
  selectionPolicy: RobotSelectionPolicy | null;
  targetSocialAccountId: string | null;
  targetSocialAccountDisplayName: string | null;
  cadenceType: RobotCadenceType;
  cadenceIntervalHours: number | null;
  scheduleDelayMinutes: number | null;
  maxRunsPerDay: number;
  aiPolicy: RobotAiPolicy;
  personaId: string | null;
  personaName: string | null;
  aiLanguageOverride: SuggestionLanguage | null;
  aiToneOverride: SuggestionTone | null;
  experimentId: string | null;
  nextRunAt: string | null;
  lastRunAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RobotRunSummary {
  id: string;
  robotId: string;
  robotName: string;
  triggerType: RobotRunTriggerType;
  status: RobotRunStatus;
  startedAt: string;
  finishedAt: string | null;
  sourceAssetId: string | null;
  contentSourceId: string | null;
  contentSourceName: string | null;
  selectionPolicy: RobotSelectionPolicy | null;
  highlightAnalysisId: string | null;
  highlightCandidateId: string | null;
  contentDraftId: string | null;
  aiPolicySnapshot: RobotAiPolicy;
  personaIdSnapshot: string | null;
  personaNameSnapshot: string | null;
  contentSuggestionId: string | null;
  experimentId: string | null;
  experimentVariantId: string | null;
  experimentVariantKey: ExperimentVariantKey | null;
  publishScheduleId: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  createdAt: string;
}

export interface RobotApprovalSummary {
  id: string;
  robotRunId: string;
  robotId: string;
  robotName: string;
  contentDraftId: string;
  draftTitle: string | null;
  draftCaption: string | null;
  socialAccountId: string;
  socialAccountDisplayName: string | null;
  proposedScheduledFor: string | null;
  status: RobotApprovalStatus;
  createdAt: string;
  decidedAt: string | null;
  decidedByUserId: string | null;
  publishScheduleId: string | null;
}

export interface CreateRobotRequest {
  name: string;
  description: string | null;
  autonomyMode: RobotAutonomyMode;
  sourcePolicy: RobotSourcePolicy;
  sourceAssetId: string | null;
  contentSourceId: string | null;
  selectionPolicy: RobotSelectionPolicy | null;
  targetSocialAccountId: string | null;
  cadenceType: RobotCadenceType;
  cadenceIntervalHours: number | null;
  scheduleDelayMinutes: number | null;
  maxRunsPerDay: number | null;
  aiPolicy: RobotAiPolicy;
  personaId: string | null;
  aiLanguageOverride: SuggestionLanguage | null;
  aiToneOverride: SuggestionTone | null;
  /** Phase 14A: when set, the Experiment's frozen variant Persona overrides personaId for experimental runs. */
  experimentId: string | null;
}

export type UpdateRobotRequest = Omit<CreateRobotRequest, 'sourcePolicy' | 'sourceAssetId' | 'contentSourceId' | 'selectionPolicy'>;
