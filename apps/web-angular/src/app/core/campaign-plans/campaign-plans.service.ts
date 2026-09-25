import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CampaignContentPlanSummary } from './campaign-plan.models';

@Injectable({ providedIn: 'root' })
export class CampaignPlansService {
  constructor(private readonly http: HttpClient) {}

  listForRun(runId: string): Observable<CampaignContentPlanSummary[]> {
    return this.http.get<CampaignContentPlanSummary[]>(
      `${environment.apiBaseUrl}/robot-runs/${runId}/campaign-plans`,
      { withCredentials: true },
    );
  }

  get(planId: string): Observable<CampaignContentPlanSummary> {
    return this.http.get<CampaignContentPlanSummary>(
      `${environment.apiBaseUrl}/campaign-plans/${planId}`,
      { withCredentials: true },
    );
  }

  apply(planId: string): Observable<CampaignContentPlanSummary> {
    return this.http.post<CampaignContentPlanSummary>(
      `${environment.apiBaseUrl}/campaign-plans/${planId}/apply`,
      {},
      { withCredentials: true },
    );
  }

  reject(planId: string): Observable<CampaignContentPlanSummary> {
    return this.http.post<CampaignContentPlanSummary>(
      `${environment.apiBaseUrl}/campaign-plans/${planId}/reject`,
      {},
      { withCredentials: true },
    );
  }

  regenerate(runId: string): Observable<CampaignContentPlanSummary> {
    return this.http.post<CampaignContentPlanSummary>(
      `${environment.apiBaseUrl}/robot-runs/${runId}/campaign-plans/regenerate`,
      {},
      { withCredentials: true },
    );
  }
}
