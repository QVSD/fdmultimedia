import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  OpsIncident,
  OpsIncidentsView,
  OpsJobDetail,
  OpsJobsView,
  OpsOverview,
  OpsPublishingView,
  OpsSchedulersView,
  OpsWorkersView,
} from './operations.models';

/** Read-mostly client of the operator-only operations API. The single write is acknowledging an incident. */
@Injectable({ providedIn: 'root' })
export class OperationsService {
  private readonly base = `${environment.apiBaseUrl}/operations`;

  constructor(private readonly http: HttpClient) {}

  overview(): Observable<OpsOverview> {
    return this.http.get<OpsOverview>(`${this.base}/overview`, { withCredentials: true });
  }

  workers(page = 0, size = 25, state?: string): Observable<OpsWorkersView> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (state) params = params.set('state', state);
    return this.http.get<OpsWorkersView>(`${this.base}/workers`, { params, withCredentials: true });
  }

  jobs(page = 0, size = 10, status?: string, type?: string): Observable<OpsJobsView> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) params = params.set('status', status);
    if (type) params = params.set('type', type);
    return this.http.get<OpsJobsView>(`${this.base}/jobs`, { params, withCredentials: true });
  }

  job(id: string): Observable<OpsJobDetail> {
    return this.http.get<OpsJobDetail>(`${this.base}/jobs/${encodeURIComponent(id)}`, { withCredentials: true });
  }

  schedulers(): Observable<OpsSchedulersView> {
    return this.http.get<OpsSchedulersView>(`${this.base}/schedulers`, { withCredentials: true });
  }

  publishing(page = 0, size = 10): Observable<OpsPublishingView> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<OpsPublishingView>(`${this.base}/publishing`, { params, withCredentials: true });
  }

  incidents(status = 'ACTIVE', page = 0, size = 25, severity?: string): Observable<OpsIncidentsView> {
    let params = new HttpParams().set('status', status).set('page', page).set('size', size);
    if (severity) params = params.set('severity', severity);
    return this.http.get<OpsIncidentsView>(`${this.base}/incidents`, { params, withCredentials: true });
  }

  acknowledge(id: string): Observable<OpsIncident> {
    return this.http.post<OpsIncident>(`${this.base}/incidents/${encodeURIComponent(id)}/acknowledge`, {}, { withCredentials: true });
  }
}
