import { RobotCampaignPlanningPolicy } from '../robots/robot.models';

export type CampaignPlanStatus = 'GENERATING' | 'READY_FOR_REVIEW' | 'APPLIED' | 'REJECTED' | 'FAILED';

export type CampaignPlanRole = 'INTRODUCTION' | 'DEEP_DIVE' | 'SUPPORTING_POINT' | 'CONCLUSION' | 'STANDALONE';

export interface CampaignContentPlanItemSummary {
  id: string;
  robotRunOutputId: string;
  sequence: number;
  role: CampaignPlanRole;
  hookGuidance: string | null;
  captionGuidance: string | null;
  ctaGuidance: string | null;
  avoidRepetitionGuidance: string | null;
}

export interface CampaignContentPlanSummary {
  id: string;
  robotRunId: string;
  revision: number;
  current: boolean;
  policy: RobotCampaignPlanningPolicy;
  status: CampaignPlanStatus;
  plannerVersion: string;
  provider: string | null;
  model: string | null;
  promptVersion: string | null;
  campaignTitle: string | null;
  campaignAngle: string | null;
  inputFingerprint: string;
  configSnapshot: Record<string, unknown> | null;
  failureCode: string | null;
  failureMessage: string | null;
  stale: boolean;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  appliedAt: string | null;
  appliedByUserId: string | null;
  rejectedAt: string | null;
  rejectedByUserId: string | null;
  items: CampaignContentPlanItemSummary[];
}
