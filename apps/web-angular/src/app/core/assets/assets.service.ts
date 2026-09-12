import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CreateClipResponse, MediaAssetSummary, MediaImportResponse } from './asset.models';

@Injectable({ providedIn: 'root' })
export class AssetsService {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<MediaAssetSummary[]> {
    return this.http.get<MediaAssetSummary[]>(`${environment.apiBaseUrl}/assets`, { withCredentials: true });
  }

  importUrl(url: string): Observable<MediaImportResponse> {
    return this.http.post<MediaImportResponse>(`${environment.apiBaseUrl}/assets/import`, { url }, { withCredentials: true });
  }

  createClip(assetId: string, startMs: number, durationMs: number): Observable<CreateClipResponse> {
    return this.http.post<CreateClipResponse>(
      `${environment.apiBaseUrl}/assets/${assetId}/clips`,
      { startMs, durationMs },
      { withCredentials: true },
    );
  }
}
