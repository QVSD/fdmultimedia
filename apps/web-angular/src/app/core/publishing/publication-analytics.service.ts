import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { PublicationAnalyticsSnapshot, PublicationAnalyticsState, PublicationAttribution } from './publication-analytics.models';

@Injectable({ providedIn: 'root' })
export class PublicationAnalyticsService {
  constructor(private readonly http: HttpClient) {}

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
