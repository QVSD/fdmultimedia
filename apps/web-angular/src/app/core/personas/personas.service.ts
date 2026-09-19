import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CreatePersonaRequest, PersonaSummary, UpdatePersonaRequest } from './persona.models';

@Injectable({ providedIn: 'root' })
export class PersonasService {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<PersonaSummary[]> {
    return this.http.get<PersonaSummary[]>(`${environment.apiBaseUrl}/personas`, { withCredentials: true });
  }

  create(request: CreatePersonaRequest): Observable<PersonaSummary> {
    return this.http.post<PersonaSummary>(`${environment.apiBaseUrl}/personas`, request, { withCredentials: true });
  }

  update(personaId: string, request: UpdatePersonaRequest): Observable<PersonaSummary> {
    return this.http.patch<PersonaSummary>(`${environment.apiBaseUrl}/personas/${personaId}`, request, { withCredentials: true });
  }

  archive(personaId: string): Observable<PersonaSummary> {
    return this.http.post<PersonaSummary>(`${environment.apiBaseUrl}/personas/${personaId}/archive`, {}, { withCredentials: true });
  }

  restore(personaId: string): Observable<PersonaSummary> {
    return this.http.post<PersonaSummary>(`${environment.apiBaseUrl}/personas/${personaId}/restore`, {}, { withCredentials: true });
  }
}
