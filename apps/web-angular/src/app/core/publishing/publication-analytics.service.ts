import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { PublicationAnalyticsSnapshot, PublicationAnalyticsState, PublicationAttribution } from './publication-analytics.models';
import { DashboardBreakdown, DashboardDimension, DashboardFilters, DashboardMetric, DashboardOptions, DashboardSummary, DashboardTrend } from './publication-dashboard.models';

@Injectable({ providedIn: 'root' })
export class PublicationAnalyticsService {
  constructor(private readonly http: HttpClient) {}

  private dashboardParams(filters: DashboardFilters): Record<string, string> {
    return Object.fromEntries(Object.entries(filters).filter(([, value]) => value != null && value !== '')) as Record<string, string>;
  }

  dashboardSummary(filters: DashboardFilters): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${environment.apiBaseUrl}/analytics/dashboard/summary`,
      { params: this.dashboardParams(filters), withCredentials: true });
  }

  dashboardTrend(filters: DashboardFilters, metric: DashboardMetric): Observable<DashboardTrend> {
    return this.http.get<DashboardTrend>(`${environment.apiBaseUrl}/analytics/dashboard/trend`,
      { params: { ...this.dashboardParams(filters), metric }, withCredentials: true });
  }

  dashboardBreakdown(filters: DashboardFilters, dimension: DashboardDimension): Observable<DashboardBreakdown> {
    return this.http.get<DashboardBreakdown>(`${environment.apiBaseUrl}/analytics/dashboard/breakdown`,
      { params: { ...this.dashboardParams(filters), dimension }, withCredentials: true });
  }

  dashboardOptions(filters: DashboardFilters): Observable<DashboardOptions> {
    return this.http.get<DashboardOptions>(`${environment.apiBaseUrl}/analytics/dashboard/filters`,
      { params: this.dashboardParams(filters), withCredentials: true });
  }

  history(id: string): Observable<PublicationAnalyticsSnapshot[]> {
    return this.http.get<PublicationAnalyticsSnapshot[]>(
      `${environment.apiBaseUrl}/publications/${id}/analytics`, { withCredentials: true });
  }

  state(id: string): Observable<PublicationAnalyticsState | null> {
    return this.http.get<PublicationAnalyticsState | null>(
      `${environment.apiBaseUrl}/publications/${id}/analytics/state`, { withCredentials: true });
  }

  attribution(id: string): Observable<PublicationAttribution> {
    return this.http.get<PublicationAttribution>(
      `${environment.apiBaseUrl}/publications/${id}/attribution`, { withCredentials: true });
  }

  refresh(id: string): Observable<PublicationAnalyticsSnapshot> {
    return this.http.post<PublicationAnalyticsSnapshot>(
      `${environment.apiBaseUrl}/publications/${id}/analytics/refresh`, {}, { withCredentials: true });
  }
}
