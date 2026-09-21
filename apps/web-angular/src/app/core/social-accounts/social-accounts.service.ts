import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { SocialAccountSummary, SocialPlatformAvailability, TikTokCreatorInfo } from './social-account.models';

@Injectable({ providedIn: 'root' })
export class SocialAccountsService {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<SocialAccountSummary[]> {
    return this.http.get<SocialAccountSummary[]>(`${environment.apiBaseUrl}/social-accounts`, { withCredentials: true });
  }

  create(platform: string, displayName: string): Observable<SocialAccountSummary> {
    return this.http.post<SocialAccountSummary>(
      `${environment.apiBaseUrl}/social-accounts`,
      { platform, displayName },
      { withCredentials: true },
    );
  }

  disconnect(accountId: string): Observable<SocialAccountSummary> {
    return this.http.post<SocialAccountSummary>(
      `${environment.apiBaseUrl}/social-accounts/${accountId}/disconnect`,
      {},
      { withCredentials: true },
    );
  }

  platforms(): Observable<SocialPlatformAvailability> {
    return this.http.get<SocialPlatformAvailability>(`${environment.apiBaseUrl}/social-accounts/platforms`, {
      withCredentials: true,
    });
  }

  connectInstagram(): Observable<{ authorizationUrl: string }> {
    return this.http.post<{ authorizationUrl: string }>(
      `${environment.apiBaseUrl}/social-accounts/instagram/connect`,
      {},
      { withCredentials: true },
    );
  }

  connectTikTok(): Observable<{ authorizationUrl: string }> {
    return this.http.post<{ authorizationUrl: string }>(`${environment.apiBaseUrl}/social-accounts/tiktok/connect`, {}, { withCredentials: true });
  }

  publishingCapabilities(accountId: string): Observable<TikTokCreatorInfo> {
    return this.http.post<TikTokCreatorInfo>(`${environment.apiBaseUrl}/social-accounts/${accountId}/publishing-capabilities`, {}, { withCredentials: true });
  }
}
