export type SocialPlatform = 'TEST' | 'INSTAGRAM' | 'TIKTOK';
export type SocialAccountStatus = 'ACTIVE' | 'DISCONNECTED' | 'ERROR';

export interface SocialAccountSummary {
  id: string;
  platform: SocialPlatform;
  displayName: string;
  externalAccountId: string | null;
  status: SocialAccountStatus;
  createdAt: string;
  updatedAt: string;
}

export interface SocialPlatformAvailability {
  TEST: boolean;
  INSTAGRAM: boolean;
  TIKTOK: boolean;
}

export interface TikTokCreatorInfo {
  username: string;
  nickname: string;
  privacyLevelOptions: string[];
  commentDisabled: boolean;
  duetDisabled: boolean;
  stitchDisabled: boolean;
  maxVideoPostDurationSec: number;
}
