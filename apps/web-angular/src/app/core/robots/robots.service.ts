import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CreateRobotRequest, RobotRunSummary, RobotSummary, UpdateRobotRequest } from './robot.models';

@Injectable({ providedIn: 'root' })
export class RobotsService {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<RobotSummary[]> {
    return this.http.get<RobotSummary[]>(`${environment.apiBaseUrl}/robots`, { withCredentials: true });
  }

  create(request: CreateRobotRequest): Observable<RobotSummary> {
    return this.http.post<RobotSummary>(`${environment.apiBaseUrl}/robots`, request, { withCredentials: true });
  }

  update(robotId: string, request: UpdateRobotRequest): Observable<RobotSummary> {
    return this.http.patch<RobotSummary>(`${environment.apiBaseUrl}/robots/${robotId}`, request, { withCredentials: true });
  }

  runNow(robotId: string): Observable<RobotRunSummary> {
    return this.http.post<RobotRunSummary>(`${environment.apiBaseUrl}/robots/${robotId}/run`, {}, { withCredentials: true });
  }

  pause(robotId: string): Observable<RobotSummary> {
    return this.http.post<RobotSummary>(`${environment.apiBaseUrl}/robots/${robotId}/pause`, {}, { withCredentials: true });
  }

  resume(robotId: string): Observable<RobotSummary> {
    return this.http.post<RobotSummary>(`${environment.apiBaseUrl}/robots/${robotId}/resume`, {}, { withCredentials: true });
  }

  runsForRobot(robotId: string): Observable<RobotRunSummary[]> {
    return this.http.get<RobotRunSummary[]>(`${environment.apiBaseUrl}/robots/${robotId}/runs`, { withCredentials: true });
  }

  allRuns(): Observable<RobotRunSummary[]> {
    return this.http.get<RobotRunSummary[]>(`${environment.apiBaseUrl}/robot-runs`, { withCredentials: true });
  }

  cancelRun(runId: string): Observable<RobotRunSummary> {
    return this.http.post<RobotRunSummary>(`${environment.apiBaseUrl}/robot-runs/${runId}/cancel`, {}, { withCredentials: true });
  }
}
