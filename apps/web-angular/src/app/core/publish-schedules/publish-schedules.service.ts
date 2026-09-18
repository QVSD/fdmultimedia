import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { PublishScheduleStatus, PublishScheduleSummary } from './publish-schedule.models';

@Injectable({ providedIn: 'root' })
export class PublishSchedulesService {
  constructor(private readonly http: HttpClient) {}

  create(draftId: string, socialAccountId: string, scheduledFor: string): Observable<PublishScheduleSummary> {
    return this.http.post<PublishScheduleSummary>(
      `${environment.apiBaseUrl}/content-drafts/${draftId}/schedules`,
      { socialAccountId, scheduledFor },
      { withCredentials: true },
    );
  }

  list(status?: PublishScheduleStatus): Observable<PublishScheduleSummary[]> {
    return this.http.get<PublishScheduleSummary[]>(`${environment.apiBaseUrl}/publish-schedules`, {
      params: status ? { status } : {},
      withCredentials: true,
    });
  }

  cancel(scheduleId: string): Observable<PublishScheduleSummary> {
    return this.http.post<PublishScheduleSummary>(
      `${environment.apiBaseUrl}/publish-schedules/${scheduleId}/cancel`,
      {},
      { withCredentials: true },
    );
  }

  reschedule(scheduleId: string, scheduledFor: string): Observable<PublishScheduleSummary> {
    return this.http.patch<PublishScheduleSummary>(
      `${environment.apiBaseUrl}/publish-schedules/${scheduleId}`,
      { scheduledFor },
      { withCredentials: true },
    );
  }
}
