import { Routes } from '@angular/router';

import { Overview } from './pages/overview/overview';
import { Compute } from './pages/compute/compute';
import { Content } from './pages/content/content';
import { Robots } from './pages/robots/robots';
import { Jobs } from './pages/jobs/jobs';
import { Analytics } from './pages/analytics/analytics';
import { Revenue } from './pages/revenue/revenue';
import { Settings } from './pages/settings/settings';

export const routes: Routes = [
  { path: '', component: Overview, title: 'Overview' },
  { path: 'compute', component: Compute, title: 'Compute' },
  { path: 'content', component: Content, title: 'Content' },
  { path: 'robots', component: Robots, title: 'Robots' },
  { path: 'jobs', component: Jobs, title: 'Jobs' },
  { path: 'analytics', component: Analytics, title: 'Analytics' },
  { path: 'revenue', component: Revenue, title: 'Revenue' },
  { path: 'settings', component: Settings, title: 'Settings' },
  { path: '**', redirectTo: '' },
];
