import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { SocialAccountSummary } from './social-account.models';

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
}
