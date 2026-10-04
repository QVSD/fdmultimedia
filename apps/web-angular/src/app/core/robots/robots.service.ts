import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CreateRobotRequest, RobotRunSummary, RobotSummary, UpdateRobotRequest } from './robot.models';
import { AutonomousProposalEligibility, RobotAdaptivePolicy, RobotAdaptivePolicyRevision, RobotConfigurationRevision } from '../publishing/robot-change-proposal.models';

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

  configurationRevisions(robotId: string): Observable<RobotConfigurationRevision[]> {
    return this.http.get<RobotConfigurationRevision[]>(`${environment.apiBaseUrl}/robots/${robotId}/configuration-revisions`,
      { withCredentials: true });
  }

  rollbackConfigurationRevision(robotId: string, revisionId: string, reason: string | null): Observable<RobotConfigurationRevision> {
    return this.http.post<RobotConfigurationRevision>(
      `${environment.apiBaseUrl}/robots/${robotId}/configuration-revisions/${revisionId}/rollback`,
      reason ? { reason } : {}, { withCredentials: true });
  }

  adaptivePolicy(robotId: string): Observable<RobotAdaptivePolicy> {
    return this.http.get<RobotAdaptivePolicy>(`${environment.apiBaseUrl}/robots/${robotId}/adaptive-policy`, { withCredentials: true });
  }

  updateAdaptivePolicy(robotId: string, policy: RobotAdaptivePolicy): Observable<RobotAdaptivePolicy> {
    return this.http.put<RobotAdaptivePolicy>(`${environment.apiBaseUrl}/robots/${robotId}/adaptive-policy`, {
      expectedRevision: policy.revision, enabled: policy.enabled,
      maxAppliedChangesPerWindow: policy.maxAppliedChangesPerWindow,
      changeBudgetWindowDays: policy.changeBudgetWindowDays, cooldownHours: policy.cooldownHours,
      requireNoActiveExperiment: policy.requireNoActiveExperiment, requireNoPendingChange: policy.requireNoPendingChange,
      requirePostChangeObservation: policy.requirePostChangeObservation,
      proposalAutomationMode: policy.proposalAutomationMode,
    }, { withCredentials: true });
  }

  adaptiveProposalEligibility(robotId: string): Observable<AutonomousProposalEligibility> {
    return this.http.get<AutonomousProposalEligibility>(`${environment.apiBaseUrl}/robots/${robotId}/adaptive-proposal-eligibility`,
      { withCredentials: true });
  }

  adaptivePolicyHistory(robotId: string): Observable<RobotAdaptivePolicyRevision[]> {
    return this.http.get<RobotAdaptivePolicyRevision[]>(`${environment.apiBaseUrl}/robots/${robotId}/adaptive-policy/revisions`, { withCredentials: true });
  }
}
