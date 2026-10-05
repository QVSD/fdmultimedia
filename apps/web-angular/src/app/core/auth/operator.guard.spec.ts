import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { lastValueFrom, Observable, of } from 'rxjs';

import { AuthService } from './auth.service';
import { isOperator, operatorGuard } from './operator.guard';

describe('operatorGuard', () => {
  let auth: {
    initialized: ReturnType<typeof vi.fn>;
    isAuthenticated: ReturnType<typeof vi.fn>;
    restoreSession: ReturnType<typeof vi.fn>;
    currentWorkspace: ReturnType<typeof vi.fn>;
  };
  let router: Router;

  beforeEach(() => {
    auth = {
      initialized: vi.fn().mockReturnValue(true),
      isAuthenticated: vi.fn().mockReturnValue(true),
      restoreSession: vi.fn(),
      currentWorkspace: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AuthService, useValue: auth }] });
    router = TestBed.inject(Router);
  });

  const run = () => TestBed.runInInjectionContext(() => operatorGuard({} as never, {} as never));

  it('treats only OWNER and ADMIN as operators', () => {
    expect(isOperator('OWNER')).toBe(true);
    expect(isOperator('ADMIN')).toBe(true);
    expect(isOperator('MEMBER')).toBe(false);
    expect(isOperator(null)).toBe(false);
    expect(isOperator(undefined)).toBe(false);
  });

  it.each(['OWNER', 'ADMIN'])('allows %s', (role) => {
    auth.currentWorkspace.mockReturnValue({ role });
    expect(run()).toBe(true);
  });

  it('redirects members to the overview', () => {
    auth.currentWorkspace.mockReturnValue({ role: 'MEMBER' });
    expect(router.serializeUrl(run() as UrlTree)).toBe('/');
  });

  it('redirects anonymous users to login', () => {
    auth.isAuthenticated.mockReturnValue(false);
    expect(router.serializeUrl(run() as UrlTree)).toBe('/login');
  });

  it('restores the session first when it is not initialised', async () => {
    auth.initialized.mockReturnValue(false);
    auth.restoreSession.mockReturnValue(of(true));
    auth.currentWorkspace.mockReturnValue({ role: 'ADMIN' });
    expect(await lastValueFrom(run() as Observable<boolean | UrlTree>)).toBe(true);
    auth.currentWorkspace.mockReturnValue({ role: 'MEMBER' });
    const result = await lastValueFrom(run() as Observable<boolean | UrlTree>);
    expect(router.serializeUrl(result as UrlTree)).toBe('/');
  });
});
