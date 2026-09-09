import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { Observable, map } from 'rxjs';

import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = (): Observable<boolean | UrlTree> | boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.initialized()) {
    return auth.isAuthenticated() ? true : router.createUrlTree(['/login']);
  }

  return auth.restoreSession().pipe(
    map((isAuthenticated) => isAuthenticated ? true : router.createUrlTree(['/login'])),
  );
};

export const loginGuard: CanActivateFn = (): Observable<boolean | UrlTree> | boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.initialized()) {
    return auth.isAuthenticated() ? router.createUrlTree(['/']) : true;
  }

  return auth.restoreSession().pipe(
    map((isAuthenticated) => isAuthenticated ? router.createUrlTree(['/']) : true),
  );
};
