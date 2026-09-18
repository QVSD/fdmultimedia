import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  SchedulingDecisionSummary,
  SchedulingOverview,
  WorkerPerformanceSummary,
} from './scheduling.models';

@Injectable({ providedIn: 'root' })
export class SchedulingService {
  constructor(private readonly http: HttpClient) {}

  overview(window = '24h'): Observable<SchedulingOverview> {
    return this.http.get<SchedulingOverview>(`${environment.apiBaseUrl}/scheduling/overview`, {
      params: { window },
      withCredentials: true,
    });
  }

  workers(window = '24h'): Observable<WorkerPerformanceSummary[]> {
    return this.http.get<WorkerPerformanceSummary[]>(`${environment.apiBaseUrl}/scheduling/workers`, {
      params: { window },
      withCredentials: true,
    });
  }

  decisions(window = '24h', limit = 25): Observable<SchedulingDecisionSummary[]> {
    return this.http.get<SchedulingDecisionSummary[]>(`${environment.apiBaseUrl}/scheduling/decisions`, {
      params: { window, limit },
      withCredentials: true,
    });
  }
}
