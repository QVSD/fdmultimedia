import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CreateSystemTestJobRequest, JobSummary } from './job.models';

@Injectable({ providedIn: 'root' })
export class JobsService {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<JobSummary[]> {
    return this.http.get<JobSummary[]>(`${environment.apiBaseUrl}/jobs`, { withCredentials: true });
  }

  createSystemTest(message: string, durationMs: number): Observable<JobSummary> {
    const request: CreateSystemTestJobRequest = {
      type: 'SYSTEM_TEST',
      payload: { message, durationMs },
    };
    return this.http.post<JobSummary>(`${environment.apiBaseUrl}/jobs`, request, { withCredentials: true });
  }
}
