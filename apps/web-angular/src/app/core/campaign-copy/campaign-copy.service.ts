import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CampaignCopySetSummary } from './campaign-copy.models';

@Injectable({ providedIn: 'root' })
export class CampaignCopyService {
  constructor(private readonly http: HttpClient) {}

  listForRun(runId: string): Observable<CampaignCopySetSummary[]> {
    return this.http.get<CampaignCopySetSummary[]>(
      `${environment.apiBaseUrl}/robot-runs/${runId}/campaign-copy-sets`,
      { withCredentials: true },
    );
  }

  get(copySetId: string): Observable<CampaignCopySetSummary> {
    return this.http.get<CampaignCopySetSummary>(
      `${environment.apiBaseUrl}/campaign-copy-sets/${copySetId}`,
      { withCredentials: true },
    );
  }

  apply(copySetId: string): Observable<CampaignCopySetSummary> {
    return this.http.post<CampaignCopySetSummary>(
      `${environment.apiBaseUrl}/campaign-copy-sets/${copySetId}/apply`,
      {},
      { withCredentials: true },
    );
  }

  reject(copySetId: string): Observable<CampaignCopySetSummary> {
    return this.http.post<CampaignCopySetSummary>(
      `${environment.apiBaseUrl}/campaign-copy-sets/${copySetId}/reject`,
      {},
      { withCredentials: true },
    );
  }

  regenerate(runId: string): Observable<CampaignCopySetSummary> {
    return this.http.post<CampaignCopySetSummary>(
      `${environment.apiBaseUrl}/robot-runs/${runId}/campaign-copy-sets/regenerate`,
      {},
      { withCredentials: true },
    );
  }
}
