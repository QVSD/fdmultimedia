import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  CreateExperimentRequest,
  DecisionReadiness,
  DecisionRecord,
  DecisionType,
  DecisionApplicationPreview,
  DecisionApplicationRecord,
  AnalysisPopulation,
  ExperimentAnalysisResponse,
  ExperimentAssignmentSummary,
  ExperimentOutcome,
  ExperimentSummary,
  UpdateExperimentRequest,
} from './experiment.models';

@Injectable({ providedIn: 'root' })
export class ExperimentsService {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<ExperimentSummary[]> {
    return this.http.get<ExperimentSummary[]>(`${environment.apiBaseUrl}/experiments`, { withCredentials: true });
  }

  get(experimentId: string): Observable<ExperimentSummary> {
    return this.http.get<ExperimentSummary>(`${environment.apiBaseUrl}/experiments/${experimentId}`, { withCredentials: true });
  }

  create(request: CreateExperimentRequest): Observable<ExperimentSummary> {
    return this.http.post<ExperimentSummary>(`${environment.apiBaseUrl}/experiments`, request, { withCredentials: true });
  }

  update(experimentId: string, request: UpdateExperimentRequest): Observable<ExperimentSummary> {
    return this.http.patch<ExperimentSummary>(`${environment.apiBaseUrl}/experiments/${experimentId}`, request, { withCredentials: true });
  }

  activate(experimentId: string): Observable<ExperimentSummary> {
    return this.http.post<ExperimentSummary>(`${environment.apiBaseUrl}/experiments/${experimentId}/activate`, {}, { withCredentials: true });
  }

  pause(experimentId: string): Observable<ExperimentSummary> {
    return this.http.post<ExperimentSummary>(`${environment.apiBaseUrl}/experiments/${experimentId}/pause`, {}, { withCredentials: true });
  }

  resume(experimentId: string): Observable<ExperimentSummary> {
    return this.http.post<ExperimentSummary>(`${environment.apiBaseUrl}/experiments/${experimentId}/resume`, {}, { withCredentials: true });
  }

  complete(experimentId: string): Observable<ExperimentSummary> {
    return this.http.post<ExperimentSummary>(`${environment.apiBaseUrl}/experiments/${experimentId}/complete`, {}, { withCredentials: true });
  }

  cancel(experimentId: string): Observable<ExperimentSummary> {
    return this.http.post<ExperimentSummary>(`${environment.apiBaseUrl}/experiments/${experimentId}/cancel`, {}, { withCredentials: true });
  }

  assignments(experimentId: string): Observable<ExperimentAssignmentSummary[]> {
    return this.http.get<ExperimentAssignmentSummary[]>(`${environment.apiBaseUrl}/experiments/${experimentId}/assignments`, {
      withCredentials: true,
    });
  }

  outcomes(experimentId: string): Observable<ExperimentOutcome> {
    return this.http.get<ExperimentOutcome>(`${environment.apiBaseUrl}/experiments/${experimentId}/outcomes`, { withCredentials: true });
  }

  /** Item 53: no metric/window query params — the Experiment's own frozen primary metric/target window are always used. */
  analysis(experimentId: string): Observable<ExperimentAnalysisResponse> {
    return this.http.get<ExperimentAnalysisResponse>(`${environment.apiBaseUrl}/experiments/${experimentId}/analysis`, {
      withCredentials: true,
    });
  }

  readiness(experimentId: string): Observable<DecisionReadiness> {
    return this.http.get<DecisionReadiness>(`${environment.apiBaseUrl}/experiments/${experimentId}/decision-readiness`, { withCredentials: true });
  }

  decisions(experimentId: string): Observable<DecisionRecord[]> {
    return this.http.get<DecisionRecord[]>(`${environment.apiBaseUrl}/experiments/${experimentId}/decisions`, { withCredentials: true });
  }

  recordDecision(experimentId: string, decision: DecisionType, population: AnalysisPopulation, rationale: string, idempotencyKey: string): Observable<DecisionRecord> {
    return this.http.post<DecisionRecord>(`${environment.apiBaseUrl}/experiments/${experimentId}/decisions`, {
      decision, selectedVariantKey: decision === 'SELECT_VARIANT_A' ? 'A' : decision === 'SELECT_VARIANT_B' ? 'B' : null,
      population, rationale, idempotencyKey,
    }, { withCredentials: true });
  }

  applicationPreview(experimentId: string, decisionId: string, robotId: string): Observable<DecisionApplicationPreview> {
    return this.http.post<DecisionApplicationPreview>(`${environment.apiBaseUrl}/experiments/${experimentId}/decisions/${decisionId}/application-preview`, { robotId }, { withCredentials: true });
  }
  applyDecision(experimentId: string, decisionId: string, request: object): Observable<DecisionApplicationRecord> {
    return this.http.post<DecisionApplicationRecord>(`${environment.apiBaseUrl}/experiments/${experimentId}/decisions/${decisionId}/applications`, request, { withCredentials: true });
  }
  applications(experimentId: string): Observable<DecisionApplicationRecord[]> {
    return this.http.get<DecisionApplicationRecord[]>(`${environment.apiBaseUrl}/experiments/${experimentId}/decision-applications`, { withCredentials: true });
  }
  rollbackPreview(applicationId: string): Observable<DecisionApplicationPreview> {
    return this.http.post<DecisionApplicationPreview>(`${environment.apiBaseUrl}/experiments/decision-applications/${applicationId}/rollback-preview`, {}, { withCredentials: true });
  }
  rollback(applicationId: string, previewFingerprint: string, idempotencyKey: string): Observable<DecisionApplicationRecord> {
    return this.http.post<DecisionApplicationRecord>(`${environment.apiBaseUrl}/experiments/decision-applications/${applicationId}/rollback`, { previewFingerprint, idempotencyKey }, { withCredentials: true });
  }
}
