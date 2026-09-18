import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  ContentSourceAssetSummary,
  ContentSourceSummary,
  CreateContentSourceRequest,
  UpdateContentSourceRequest,
} from './content-source.models';

@Injectable({ providedIn: 'root' })
export class ContentSourcesService {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<ContentSourceSummary[]> {
    return this.http.get<ContentSourceSummary[]>(`${environment.apiBaseUrl}/content-sources`, { withCredentials: true });
  }

  create(request: CreateContentSourceRequest): Observable<ContentSourceSummary> {
    return this.http.post<ContentSourceSummary>(`${environment.apiBaseUrl}/content-sources`, request, { withCredentials: true });
  }

  update(sourceId: string, request: UpdateContentSourceRequest): Observable<ContentSourceSummary> {
    return this.http.patch<ContentSourceSummary>(`${environment.apiBaseUrl}/content-sources/${sourceId}`, request, { withCredentials: true });
  }

  pause(sourceId: string): Observable<ContentSourceSummary> {
    return this.http.post<ContentSourceSummary>(`${environment.apiBaseUrl}/content-sources/${sourceId}/pause`, {}, { withCredentials: true });
  }

  resume(sourceId: string): Observable<ContentSourceSummary> {
    return this.http.post<ContentSourceSummary>(`${environment.apiBaseUrl}/content-sources/${sourceId}/resume`, {}, { withCredentials: true });
  }

  listAssets(sourceId: string): Observable<ContentSourceAssetSummary[]> {
    return this.http.get<ContentSourceAssetSummary[]>(`${environment.apiBaseUrl}/content-sources/${sourceId}/assets`, { withCredentials: true });
  }

  addAsset(sourceId: string, mediaAssetId: string): Observable<ContentSourceAssetSummary> {
    return this.http.post<ContentSourceAssetSummary>(
      `${environment.apiBaseUrl}/content-sources/${sourceId}/assets`,
      { mediaAssetId },
      { withCredentials: true },
    );
  }

  removeAsset(sourceId: string, mediaAssetId: string): Observable<void> {
    return this.http.delete<void>(`${environment.apiBaseUrl}/content-sources/${sourceId}/assets/${mediaAssetId}`, { withCredentials: true });
  }
}
