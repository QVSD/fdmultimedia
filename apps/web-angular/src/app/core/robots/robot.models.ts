export type RobotStatus = 'ACTIVE' | 'PAUSED' | 'DISABLED';
export type RobotAutonomyMode = 'DRAFT_ONLY' | 'REVIEW_REQUIRED' | 'AUTO_SCHEDULE';
export type RobotHighlightStrategy = 'TOP_HIGHLIGHT';
export type RobotCadenceType = 'MANUAL_ONLY' | 'INTERVAL';
export type RobotRunTriggerType = 'MANUAL' | 'SCHEDULED';
export type RobotRunStatus =
  | 'RUNNING'
  | 'WAITING_FOR_DRAFT'
  | 'WAITING_FOR_REVIEW'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';
export type RobotApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface RobotSummary {
  id: string;
  name: string;
  description: string | null;
  status: RobotStatus;
  autonomyMode: RobotAutonomyMode;
  highlightStrategy: RobotHighlightStrategy;
  sourceAssetId: string;
  sourceAssetFilename: string | null;
  targetSocialAccountId: string | null;
  targetSocialAccountDisplayName: string | null;
  cadenceType: RobotCadenceType;
  cadenceIntervalHours: number | null;
  scheduleDelayMinutes: number | null;
  maxRunsPerDay: number;
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
  sourceAssetId: string;
  highlightAnalysisId: string | null;
  highlightCandidateId: string | null;
  contentDraftId: string | null;
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
  sourceAssetId: string;
  targetSocialAccountId: string | null;
  cadenceType: RobotCadenceType;
  cadenceIntervalHours: number | null;
  scheduleDelayMinutes: number | null;
  maxRunsPerDay: number | null;
}

export type UpdateRobotRequest = Omit<CreateRobotRequest, 'sourceAssetId'>;
