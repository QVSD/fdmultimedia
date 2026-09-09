import { HttpClient } from '@angular/common/http';
import { Injectable, computed, signal } from '@angular/core';
import { Observable, catchError, map, of, tap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthSession, LoginRequest } from './auth.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly sessionState = signal<AuthSession | null>(null);
  private readonly initializedState = signal(false);

  readonly session = this.sessionState.asReadonly();
  readonly initialized = this.initializedState.asReadonly();
  readonly isAuthenticated = computed(() => this.sessionState() !== null);
  readonly currentUser = computed(() => this.sessionState()?.user ?? null);
  readonly currentWorkspace = computed(() => this.sessionState()?.currentWorkspace ?? null);

  constructor(private readonly http: HttpClient) {}

  restoreSession(): Observable<boolean> {
    return this.http.get<AuthSession>(`${environment.apiBaseUrl}/auth/me`, { withCredentials: true }).pipe(
      tap((session) => this.sessionState.set(session)),
      map(() => true),
      catchError(() => {
        this.sessionState.set(null);
        return of(false);
      }),
      tap(() => this.initializedState.set(true)),
    );
  }

  login(request: LoginRequest): Observable<AuthSession> {
    return this.http.post<AuthSession>(`${environment.apiBaseUrl}/auth/login`, request, { withCredentials: true }).pipe(
      tap((session) => {
        this.sessionState.set(session);
        this.initializedState.set(true);
      }),
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${environment.apiBaseUrl}/auth/logout`, {}, { withCredentials: true }).pipe(
      tap(() => {
        this.sessionState.set(null);
        this.initializedState.set(true);
      }),
      catchError((error) => {
        this.sessionState.set(null);
        this.initializedState.set(true);
        return throwError(() => error);
      }),
    );
  }
}
