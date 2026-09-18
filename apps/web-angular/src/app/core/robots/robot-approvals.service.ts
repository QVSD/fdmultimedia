import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { RobotApprovalStatus, RobotApprovalSummary } from './robot.models';

@Injectable({ providedIn: 'root' })
export class RobotApprovalsService {
  constructor(private readonly http: HttpClient) {}

  list(status?: RobotApprovalStatus): Observable<RobotApprovalSummary[]> {
    return this.http.get<RobotApprovalSummary[]>(`${environment.apiBaseUrl}/robot-approvals`, {
      params: status ? { status } : {},
      withCredentials: true,
    });
  }

  approve(approvalId: string, scheduledFor: string | null): Observable<RobotApprovalSummary> {
    return this.http.post<RobotApprovalSummary>(
      `${environment.apiBaseUrl}/robot-approvals/${approvalId}/approve`,
      { scheduledFor },
      { withCredentials: true },
    );
  }

  reject(approvalId: string): Observable<RobotApprovalSummary> {
    return this.http.post<RobotApprovalSummary>(
      `${environment.apiBaseUrl}/robot-approvals/${approvalId}/reject`,
      {},
      { withCredentials: true },
    );
  }
}
