import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CreateClipResponse, HighlightAnalysisSummary, MediaAssetSummary, MediaImportResponse, MediaTranscriptSummary } from './asset.models';

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

  createSocialVertical(assetId: string): Observable<CreateClipResponse> {
    return this.http.post<CreateClipResponse>(
      `${environment.apiBaseUrl}/assets/${assetId}/social-vertical`,
      {},
      { withCredentials: true },
    );
  }

  createHighlightAnalysis(assetId: string, analyzer = 'DETERMINISTIC_V1'): Observable<HighlightAnalysisSummary> {
    return this.http.post<HighlightAnalysisSummary>(
      `${environment.apiBaseUrl}/assets/${assetId}/highlight-analyses`,
      { analyzer },
      { withCredentials: true },
    );
  }

  listHighlightAnalyses(assetId: string): Observable<HighlightAnalysisSummary[]> {
    return this.http.get<HighlightAnalysisSummary[]>(
      `${environment.apiBaseUrl}/assets/${assetId}/highlight-analyses`,
      { withCredentials: true },
    );
  }

  createTranscript(assetId: string): Observable<MediaTranscriptSummary> {
    return this.http.post<MediaTranscriptSummary>(
      `${environment.apiBaseUrl}/assets/${assetId}/transcripts`,
      {},
      { withCredentials: true },
    );
  }

  listTranscripts(assetId: string): Observable<MediaTranscriptSummary[]> {
    return this.http.get<MediaTranscriptSummary[]>(
      `${environment.apiBaseUrl}/assets/${assetId}/transcripts`,
      { withCredentials: true },
    );
  }

  createClipFromHighlightCandidate(candidateId: string): Observable<CreateClipResponse> {
    return this.http.post<CreateClipResponse>(
      `${environment.apiBaseUrl}/highlight-candidates/${candidateId}/create-clip`,
      {},
      { withCredentials: true },
    );
  }
}
