import { Routes } from '@angular/router';

import { Overview } from './pages/overview/overview';
import { Compute } from './pages/compute/compute';
import { Content } from './pages/content/content';
import { Robots } from './pages/robots/robots';
import { Personas } from './pages/personas/personas';
import { Jobs } from './pages/jobs/jobs';
import { Analytics } from './pages/analytics/analytics';
import { Revenue } from './pages/revenue/revenue';
import { Settings } from './pages/settings/settings';
import { Login } from './pages/login/login';
import { authGuard, loginGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: 'login', component: Login, canActivate: [loginGuard], title: 'Sign in' },
  { path: '', component: Overview, canActivate: [authGuard], title: 'Overview' },
  { path: 'compute', component: Compute, canActivate: [authGuard], title: 'Compute' },
  { path: 'content', component: Content, canActivate: [authGuard], title: 'Content' },
  { path: 'robots', component: Robots, canActivate: [authGuard], title: 'Robots' },
  { path: 'personas', component: Personas, canActivate: [authGuard], title: 'Personas' },
  { path: 'jobs', component: Jobs, canActivate: [authGuard], title: 'Jobs' },
  { path: 'analytics', component: Analytics, canActivate: [authGuard], title: 'Analytics' },
  { path: 'revenue', component: Revenue, canActivate: [authGuard], title: 'Revenue' },
  { path: 'settings', component: Settings, canActivate: [authGuard], title: 'Settings' },
  { path: '**', redirectTo: '' },
];
