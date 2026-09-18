import { SocialPlatform } from '../social-accounts/social-account.models';

export type PublishScheduleStatus = 'SCHEDULED' | 'DISPATCHED' | 'CANCELLED' | 'FAILED';

export interface PublishScheduleSummary {
  id: string;
  contentDraftId: string;
  draftTitle: string | null;
  mediaAssetId: string;
  mediaAssetFilename: string | null;
  socialAccountId: string;
  socialAccountDisplayName: string;
  platform: SocialPlatform;
  captionSnapshot: string | null;
  scheduledFor: string;
  status: PublishScheduleStatus;
  publicationId: string | null;
  createdAt: string;
  updatedAt: string;
  dispatchedAt: string | null;
  cancelledAt: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  dispatchDelayMs: number | null;
}
