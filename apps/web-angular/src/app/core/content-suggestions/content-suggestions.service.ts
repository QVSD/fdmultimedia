import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ContentSuggestionSummary, CreateContentSuggestionRequest } from './content-suggestion.models';

@Injectable({ providedIn: 'root' })
export class ContentSuggestionsService {
  constructor(private readonly http: HttpClient) {}

  listForDraft(draftId: string): Observable<ContentSuggestionSummary[]> {
    return this.http.get<ContentSuggestionSummary[]>(
      `${environment.apiBaseUrl}/content-drafts/${draftId}/suggestions`,
      { withCredentials: true },
    );
  }

  create(draftId: string, request: CreateContentSuggestionRequest): Observable<ContentSuggestionSummary> {
    return this.http.post<ContentSuggestionSummary>(
      `${environment.apiBaseUrl}/content-drafts/${draftId}/suggestions`,
      request,
      { withCredentials: true },
    );
  }

  apply(suggestionId: string): Observable<ContentSuggestionSummary> {
    return this.http.post<ContentSuggestionSummary>(
      `${environment.apiBaseUrl}/content-suggestions/${suggestionId}/apply`,
      {},
      { withCredentials: true },
    );
  }

  discard(suggestionId: string): Observable<ContentSuggestionSummary> {
    return this.http.post<ContentSuggestionSummary>(
      `${environment.apiBaseUrl}/content-suggestions/${suggestionId}/discard`,
      {},
      { withCredentials: true },
    );
  }
}
