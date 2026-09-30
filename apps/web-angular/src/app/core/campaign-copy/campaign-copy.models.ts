export type CampaignCopySetStatus = 'GENERATING' | 'READY_FOR_REVIEW' | 'APPLIED' | 'REJECTED' | 'FAILED';

export interface CampaignCopyItemSummary {
  id: string;
  robotRunOutputId: string;
  campaignContentPlanItemId: string;
  sequence: number;
  hook: string;
  caption: string;
  hashtags: string[];
  shortTitle: string | null;
  continuityNote: string | null;
  contentSuggestionId: string | null;
}

export interface CampaignCopySetSummary {
  id: string;
  robotRunId: string;
  campaignPlanId: string;
  campaignPlanRevision: number;
  revision: number;
  current: boolean;
  status: CampaignCopySetStatus;
  generatorVersion: string;
  provider: string | null;
  model: string | null;
  promptVersion: string | null;
  seriesTitle: string | null;
  sharedFraming: string | null;
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
  items: CampaignCopyItemSummary[];
}
