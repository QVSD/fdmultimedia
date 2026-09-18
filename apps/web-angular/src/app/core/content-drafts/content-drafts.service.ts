import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ContentDraftSummary } from './content-draft.models';

@Injectable({ providedIn: 'root' })
export class ContentDraftsService {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<ContentDraftSummary[]> {
    return this.http.get<ContentDraftSummary[]>(`${environment.apiBaseUrl}/content-drafts`, { withCredentials: true });
  }

  get(draftId: string): Observable<ContentDraftSummary> {
    return this.http.get<ContentDraftSummary>(`${environment.apiBaseUrl}/content-drafts/${draftId}`, { withCredentials: true });
  }

  createFromAsset(assetId: string, title: string | null, caption: string | null): Observable<ContentDraftSummary> {
    return this.http.post<ContentDraftSummary>(
      `${environment.apiBaseUrl}/content-drafts`,
      { assetId, title, caption },
      { withCredentials: true },
    );
  }

  createFromHighlightCandidate(candidateId: string): Observable<ContentDraftSummary> {
    return this.http.post<ContentDraftSummary>(
      `${environment.apiBaseUrl}/content-drafts/from-highlight/${candidateId}`,
      {},
      { withCredentials: true },
    );
  }

  update(draftId: string, title: string | null, caption: string | null): Observable<ContentDraftSummary> {
    return this.http.patch<ContentDraftSummary>(
      `${environment.apiBaseUrl}/content-drafts/${draftId}`,
      { title, caption },
      { withCredentials: true },
    );
  }

  publish(draftId: string, socialAccountId: string): Observable<ContentDraftSummary> {
    return this.http.post<ContentDraftSummary>(
      `${environment.apiBaseUrl}/content-drafts/${draftId}/publish`,
      { socialAccountId },
      { withCredentials: true },
    );
  }

  retryPreparation(draftId: string): Observable<ContentDraftSummary> {
    return this.http.post<ContentDraftSummary>(
      `${environment.apiBaseUrl}/content-drafts/${draftId}/retry-preparation`,
      {},
      { withCredentials: true },
    );
  }
}
