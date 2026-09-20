import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { PublicationSummary } from './publishing.models';

@Injectable({ providedIn: 'root' })
export class PublishingService {
  constructor(private readonly http: HttpClient) {}

  createPublication(assetId: string, socialAccountId: string, caption: string | null): Observable<PublicationSummary> {
    return this.http.post<PublicationSummary>(
      `${environment.apiBaseUrl}/assets/${assetId}/publications`,
      { socialAccountId, caption },
      { withCredentials: true },
    );
  }

  listForAsset(assetId: string): Observable<PublicationSummary[]> {
    return this.http.get<PublicationSummary[]>(`${environment.apiBaseUrl}/publications`, {
      params: { assetId },
      withCredentials: true,
    });
  }

  list(): Observable<PublicationSummary[]> {
    return this.http.get<PublicationSummary[]>(`${environment.apiBaseUrl}/publications`, { withCredentials: true });
  }
}
