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
