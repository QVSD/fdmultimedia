import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { Observable, map } from 'rxjs';

import { AuthService } from './auth.service';

/** Operations visibility is for workspace OWNER and ADMIN only; the API enforces the same rule. */
export function isOperator(role: string | null | undefined): boolean {
  return role === 'OWNER' || role === 'ADMIN';
}

export const operatorGuard: CanActivateFn = (): Observable<boolean | UrlTree> | boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const decide = (authenticated: boolean): boolean | UrlTree => {
    if (!authenticated) return router.createUrlTree(['/login']);
    return isOperator(auth.currentWorkspace()?.role) ? true : router.createUrlTree(['/']);
  };

  if (auth.initialized()) return decide(auth.isAuthenticated());
  return auth.restoreSession().pipe(map(decide));
};
