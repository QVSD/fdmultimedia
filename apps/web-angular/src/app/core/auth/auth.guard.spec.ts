import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { Observable, lastValueFrom, of } from 'rxjs';

import { AuthService } from './auth.service';
import { authGuard, loginGuard } from './auth.guard';

describe('authGuard', () => {
  let auth: {
    initialized: ReturnType<typeof vi.fn>;
    isAuthenticated: ReturnType<typeof vi.fn>;
    restoreSession: ReturnType<typeof vi.fn>;
  };
  let router: Router;

  beforeEach(() => {
    auth = {
      initialized: vi.fn(),
      isAuthenticated: vi.fn(),
      restoreSession: vi.fn(),
    };

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
      ],
    });

    router = TestBed.inject(Router);
  });

  it('allows authenticated users through protected routes', () => {
    auth.initialized.mockReturnValue(true);
    auth.isAuthenticated.mockReturnValue(true);

    const result = TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));

    expect(result).toBe(true);
  });

  it('redirects unauthenticated users to login', () => {
    auth.initialized.mockReturnValue(true);
    auth.isAuthenticated.mockReturnValue(false);

    const result = TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));

    expect(router.serializeUrl(result as UrlTree)).toBe('/login');
  });

  it('restores session before deciding when auth state is unknown', async () => {
    auth.initialized.mockReturnValue(false);
    auth.restoreSession.mockReturnValue(of(true));

    const result = TestBed.runInInjectionContext(
      () => authGuard({} as never, {} as never),
    ) as Observable<boolean | UrlTree>;

    expect(await lastValueFrom(result)).toBe(true);
  });

  it('keeps unauthenticated users on login', () => {
    auth.initialized.mockReturnValue(true);
    auth.isAuthenticated.mockReturnValue(false);

    const result = TestBed.runInInjectionContext(() => loginGuard({} as never, {} as never));

    expect(result).toBe(true);
  });

  it('redirects authenticated users away from login', () => {
    auth.initialized.mockReturnValue(true);
    auth.isAuthenticated.mockReturnValue(true);

    const result = TestBed.runInInjectionContext(() => loginGuard({} as never, {} as never));

    expect(router.serializeUrl(result as UrlTree)).toBe('/');
  });
});
