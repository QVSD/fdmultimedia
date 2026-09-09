import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';

import { AuthService } from './auth.service';
import { AuthSession } from './auth.models';

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  const session: AuthSession = {
    user: { id: 'user-id', email: 'owner@example.com', displayName: 'Owner' },
    currentWorkspace: { id: 'workspace-id', name: 'FD Multimedia', slug: 'fd-multimedia', role: 'OWNER' },
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })),
        provideHttpClientTesting(),
      ],
    });

    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('restores authentication from /api/auth/me', () => {
    service.restoreSession().subscribe((authenticated) => {
      expect(authenticated).toBe(true);
      expect(service.currentUser()?.email).toBe('owner@example.com');
      expect(service.currentWorkspace()?.role).toBe('OWNER');
    });

    http.expectOne('/api/auth/me').flush(session);
  });

  it('clears authentication when session restoration fails', () => {
    service.restoreSession().subscribe((authenticated) => {
      expect(authenticated).toBe(false);
      expect(service.currentUser()).toBeNull();
    });

    http.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
  });

  it('logs in without storing secrets locally', () => {
    service.login({ email: 'owner@example.com', password: 'secret' }).subscribe(() => {
      expect(service.isAuthenticated()).toBe(true);
      expect(localStorage.length).toBe(0);
    });

    const request = http.expectOne('/api/auth/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ email: 'owner@example.com', password: 'secret' });
    request.flush(session);
  });

  it('logout clears auth state', () => {
    service.login({ email: 'owner@example.com', password: 'secret' }).subscribe();
    http.expectOne('/api/auth/login').flush(session);

    service.logout().subscribe(() => {
      expect(service.isAuthenticated()).toBe(false);
    });

    const request = http.expectOne('/api/auth/logout');
    expect(request.request.method).toBe('POST');
    request.flush(null);
  });
});
