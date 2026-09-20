import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  CreateExperimentRequest,
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
}
