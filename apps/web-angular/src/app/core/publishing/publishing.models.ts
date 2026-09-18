import { SocialPlatform } from '../social-accounts/social-account.models';

export type PublicationStatus = 'PENDING' | 'PUBLISHING' | 'PUBLISHED' | 'FAILED' | 'CANCELLED';
export type PublishingAttemptOutcome = 'SUCCEEDED' | 'RETRYABLE_FAILED' | 'FAILED';

export interface PublishingAttemptSummary {
  id: string;
  jobAttempt: number;
  workerId: string | null;
  workerName: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  outcome: PublishingAttemptOutcome;
  providerRequestId: string | null;
  providerPublicationId: string | null;
  errorCode: string | null;
  errorMessage: string | null;
}

export interface PublicationSummary {
  id: string;
  assetId: string;
  assetFilename: string | null;
  socialAccountId: string;
  socialAccountDisplayName: string;
  platform: SocialPlatform;
  status: PublicationStatus;
  jobId: string | null;
  caption: string | null;
  providerRequestId: string | null;
  providerPublicationId: string | null;
  createdAt: string;
  updatedAt: string;
  publishedAt: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  attempts: PublishingAttemptSummary[];
}
