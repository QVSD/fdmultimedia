import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import {
  OpsIncident,
  OpsIncidentsView,
  OpsJobsView,
  OpsOverview,
  OpsPublishingView,
  OpsSchedulersView,
  OpsWorkersView,
} from '../../core/operations/operations.models';
import { OperationsService } from '../../core/operations/operations.service';
import { Operations } from './operations';

const NOW = '2026-10-05T12:00:00Z';

function incident(overrides: Partial<OpsIncident> = {}): OpsIncident {
  return {
    id: 'i-1',
    key: 'DEPENDENCY:MINIO:UNAVAILABLE',
    severity: 'CRITICAL',
    status: 'ACTIVE',
    category: 'DEPENDENCY',
    title: 'Object storage (MinIO) is unavailable',
    conditionCode: 'UNAVAILABLE',
    detail: 'reason=TIMEOUT',
    suggestedAction: 'Check MinIO health and credentials.',
    subjectType: 'DEPENDENCY',
    subjectId: null,
    firstObservedAt: NOW,
    lastObservedAt: NOW,
    resolvedAt: null,
    acknowledgedAt: null,
    acknowledged: false,
    persisted: true,
    ...overrides,
  };
}

function overview(overrides: Partial<OpsOverview> = {}): OpsOverview {
  return {
    engineVersion: 'OPERATIONS_OVERVIEW_V1',
    observedAt: NOW,
    overallStatus: 'HEALTHY',
    api: { status: 'HEALTHY', instance: 'api-1', uptimeSeconds: 3700, liveness: 'UP', readiness: 'UP' },
    dependencies: [
      { name: 'POSTGRES', status: 'HEALTHY', detail: 'UP', configured: true, observedAt: NOW, ageSeconds: 1, cached: false, probeMillis: 2 },
      { name: 'MINIO', status: 'HEALTHY', detail: 'UP', configured: true, observedAt: NOW, ageSeconds: 1, cached: false, probeMillis: 4 },
    ],
    workers: { status: 'HEALTHY', total: 2, online: 2, stale: 0, offline: 0, recentlySeenOffline: 0, staleAfterSeconds: 20, offlineAfterSeconds: 30 },
    jobs: {
      status: 'HEALTHY', queued: 0, assigned: 0, running: 0, leaseExpired: 0, failedRecent: 1, succeededRecent: 5,
      oldestQueuedAgeSeconds: null, backlogSeverity: null, backlogWarningSeconds: 300, backlogCriticalSeconds: 1800, recentWindowHours: 24,
    },
    schedulers: { status: 'HEALTHY', total: 11, idle: 6, running: 0, degraded: 0, failed: 0, unknown: 5 },
    publishing: {
      status: 'HEALTHY', scheduledDue: 0, scheduledOverdue: 0, publishing: 0, publishedRecent: 3, failedRecent: 0, outcomeUnknown: 0,
      oldestOverdueAgeSeconds: null, overdueWarningSeconds: 300, overdueCriticalSeconds: 1800, recentWindowHours: 24,
    },
    automation: {
      status: 'HEALTHY', autoProposeRobots: 1, pendingProposals: 2, activeAuthorizations: 0, guardrailBlocked: 0,
      activeSafetyObservations: 1, openRollbackRecommendations: 0, memorySuppressedTransitions: 3,
    },
    incidents: { status: 'HEALTHY', active: 0, critical: 0, warning: 0, info: 0, unacknowledged: 0 },
    topIncidents: [],
    refreshHintSeconds: 15,
    ...overrides,
  };
}

const page = <T>(items: T[]) => ({ items, page: 0, size: 10, total: items.length });

describe('Operations', () => {
  let fixture: ComponentFixture<Operations>;
  let component: Operations;
  let service: {
    overview: ReturnType<typeof vi.fn>;
    workers: ReturnType<typeof vi.fn>;
    jobs: ReturnType<typeof vi.fn>;
    schedulers: ReturnType<typeof vi.fn>;
    publishing: ReturnType<typeof vi.fn>;
    incidents: ReturnType<typeof vi.fn>;
    acknowledge: ReturnType<typeof vi.fn>;
  };

  const workersView: OpsWorkersView = {
    summary: overview().workers,
    workers: page([{
      id: 'w1', name: 'worker-a', state: 'ONLINE', lastHeartbeatAt: NOW, activeJobs: 1, maxActiveJobs: 2, capabilities: [],
      agentVersion: '1', currentJobId: null, lastCompletedAt: null, lastFailedAt: null,
    }]),
  };
  const jobsView: OpsJobsView = {
    summary: overview().jobs,
    jobs: page([{
      id: 'j1', type: 'PUBLISH_MEDIA', status: 'FAILED', queuedAt: NOW, finishedAt: NOW, attemptCount: 3, maxAttempts: 3,
      retryState: 'EXHAUSTED', failureCategory: 'PROVIDER_REJECTED', failureMessage: 'Upload failed [url]', leaseExpiresAt: null,
    }]),
  };
  const schedulersView: OpsSchedulersView = {
    summary: overview().schedulers,
    schedulers: [{
      name: 'publish-schedule-dispatch', enabled: true, state: 'IDLE', stale: false, cadenceSeconds: 15, staleAfterSeconds: 120,
      lastStartedAt: NOW, lastCompletedAt: NOW, lastSucceededAt: NOW, lastFailedAt: null, lastDurationMs: 4, processedCount: 0,
      resultCount: 0, lastFailureCode: null, reportingInstances: 4, batchBound: 50, classification: 'A',
    }, {
      name: 'adaptive-memory', enabled: true, state: 'DEGRADED', stale: true, cadenceSeconds: 3600, staleAfterSeconds: 10800,
      lastStartedAt: NOW, lastCompletedAt: NOW, lastSucceededAt: NOW, lastFailedAt: null, lastDurationMs: 4, processedCount: 0,
      resultCount: 0, lastFailureCode: null, reportingInstances: 1, batchBound: 100, classification: 'A',
    }],
  };
  const publishingView: OpsPublishingView = {
    summary: overview().publishing,
    providers: [{ provider: 'TEST', status: 'HEALTHY', lastSuccessAt: NOW, lastFailureAt: null, failuresSinceLastSuccess: 0 }],
    attention: page([{
      id: 'p1', provider: 'TIKTOK', status: 'PUBLISHING', createdAt: NOW, finishedAt: null, attempts: 1, failureCategory: null,
      outcomeUnknown: true, retryGuidance: 'VERIFY_WITH_PROVIDER_BEFORE_ANY_RETRY',
    }]),
  };
  const incidentsView = (items: OpsIncident[]): OpsIncidentsView => ({
    summary: { status: items.length ? 'DEGRADED' : 'HEALTHY', active: items.length, critical: 0, warning: 0, info: 0, unacknowledged: items.length },
    incidents: page(items),
  });

  async function create(): Promise<void> {
    fixture = TestBed.createComponent(Operations);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const text = () => fixture.nativeElement.textContent as string;
  const field = (name: string) => (component as unknown as Record<string, (...args: unknown[]) => unknown>)[name].bind(component);

  beforeEach(() => {
    service = {
      overview: vi.fn().mockReturnValue(of(overview())),
      workers: vi.fn().mockReturnValue(of(workersView)),
      jobs: vi.fn().mockReturnValue(of(jobsView)),
      schedulers: vi.fn().mockReturnValue(of(schedulersView)),
      publishing: vi.fn().mockReturnValue(of(publishingView)),
      incidents: vi.fn().mockReturnValue(of(incidentsView([]))),
      acknowledge: vi.fn().mockReturnValue(of(incident({ acknowledged: true }))),
    };
    TestBed.configureTestingModule({
      imports: [Operations],
      providers: [provideRouter([]), { provide: OperationsService, useValue: service as unknown as OperationsService }],
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
  });

  it('renders the overall status as text and an icon, not only a color', async () => {
    await create();
    const badge = fixture.nativeElement.querySelector('[data-testid="overall-status"]') as HTMLElement;
    expect(badge.textContent).toContain('Healthy');
    expect(badge.querySelector('[aria-hidden="true"]')?.textContent).toContain('✓');
    expect(fixture.nativeElement.querySelector('.ops__overall')?.getAttribute('role')).toBe('status');
  });

  it('renders every section from the overview and the drill-down panels', async () => {
    await create();
    const content = text();
    for (const heading of ['Dependencies', 'Workers', 'Jobs', 'Schedulers', 'Publishing', 'Automation', 'Incidents']) {
      expect(content).toContain(heading);
    }
    expect(content).toContain('POSTGRES');
    expect(content).toContain('MINIO');
    expect(fixture.nativeElement.querySelector('[data-testid="workers-online"]').textContent).toContain('2');
    expect(fixture.nativeElement.querySelector('[data-testid="jobs-oldest"]').textContent).toContain('none queued');
    expect(content).toContain('worker-a');
    expect(content).toContain('PROVIDER_REJECTED');
    expect(content).toContain('Upload failed [url]');
    expect(content).toContain('publish-schedule-dispatch');
    expect(content).toContain('STALE');
    expect(content).toContain('OUTCOME UNKNOWN');
    expect(content).toContain('Verify on the provider before any retry.');
    expect(content).toContain('No active incidents.');
  });

  it('shows the oldest queued age when work is waiting', async () => {
    const waiting = overview({ jobs: { ...overview().jobs, queued: 3, oldestQueuedAgeSeconds: 640, status: 'DEGRADED' } });
    service.overview.mockReturnValue(of(waiting));
    await create();
    expect(fixture.nativeElement.querySelector('[data-testid="jobs-oldest"]').textContent).toContain('10 min');
  });

  it('shows incidents with a detail panel and a deterministic suggested action', async () => {
    const down = incident();
    service.overview.mockReturnValue(of(overview({
      overallStatus: 'ACTION_REQUIRED', topIncidents: [down],
      incidents: { status: 'DEGRADED', active: 1, critical: 1, warning: 0, info: 0, unacknowledged: 1 },
    })));
    service.incidents.mockReturnValue(of(incidentsView([down, incident({ id: 'i-2', key: 'WORKERS:NONE_ONLINE', category: 'WORKERS', severity: 'WARNING', title: 'No Worker is online', suggestedAction: 'Start a Worker.' })])));
    await create();
    expect(fixture.nativeElement.querySelector('[data-testid="overall-status"]').textContent).toContain('Action required');
    expect(fixture.nativeElement.querySelector('[data-testid="incident-count"]').textContent).toContain('1 active');
    expect(fixture.nativeElement.querySelector('[data-testid="suggested-action"]').textContent).toContain('Check MinIO health');
    (fixture.nativeElement.querySelectorAll('.incident')[1] as HTMLElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="suggested-action"]').textContent).toContain('Start a Worker.');
    expect(fixture.nativeElement.querySelector('.incident-detail a')?.textContent).toContain('Open Compute');
    expect(text()).toContain('Critical');
    expect(text()).toContain('Warning');
  });

  it('acknowledges an incident and refreshes', async () => {
    service.incidents.mockReturnValue(of(incidentsView([incident()])));
    await create();
    (fixture.nativeElement.querySelector('[data-testid="acknowledge"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(service.acknowledge).toHaveBeenCalledWith('i-1');
    expect(service.overview).toHaveBeenCalledTimes(2);
    expect(text()).toContain('does not resolve it');
  });

  it('does not offer acknowledgement for acknowledged or not-yet-recorded incidents', async () => {
    service.incidents.mockReturnValue(of(incidentsView([incident({ acknowledged: true, acknowledgedAt: NOW })])));
    await create();
    expect(fixture.nativeElement.querySelector('[data-testid="acknowledge"]')).toBeNull();
    expect(text()).toContain('Acknowledged');
    service.incidents.mockReturnValue(of(incidentsView([incident({ persisted: false })])));
    await create();
    expect((fixture.nativeElement.querySelector('[data-testid="acknowledge"]') as HTMLButtonElement).disabled).toBe(true);
  });

  it('reports an acknowledgement failure without losing the page', async () => {
    service.incidents.mockReturnValue(of(incidentsView([incident()])));
    service.acknowledge.mockReturnValue(throwError(() => new Error('409')));
    await create();
    (fixture.nativeElement.querySelector('[data-testid="acknowledge"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(text()).toContain('could not be acknowledged');
    expect(text()).toContain('Dependencies');
  });

  it('shows a partial failure only in the affected panel', async () => {
    service.workers.mockReturnValue(throwError(() => new Error('boom')));
    await create();
    expect(text()).toContain('Status temporarily unavailable.');
    expect(fixture.nativeElement.querySelectorAll('[role="alert"]').length).toBe(1);
    expect(text()).toContain('MINIO');
    expect(text()).toContain('publish-schedule-dispatch');
    expect(fixture.nativeElement.querySelector('[data-testid="status-unavailable"]')).toBeNull();
  });

  it('keeps the last good data and states its age when a refresh fails', async () => {
    await create();
    service.overview.mockReturnValue(throwError(() => new Error('down')));
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 150_000);
    (fixture.nativeElement.querySelector('[data-testid="refresh"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const banner = fixture.nativeElement.querySelector('[data-testid="status-unavailable"]') as HTMLElement;
    expect(banner.textContent).toContain('Status temporarily unavailable.');
    expect(banner.textContent).toContain('Showing data from');
    expect(text()).toContain('MINIO');
  });

  it('survives the overview being unavailable from the start', async () => {
    service.overview.mockReturnValue(throwError(() => new Error('down')));
    await create();
    expect(fixture.nativeElement.querySelector('[data-testid="status-unavailable"]')).not.toBeNull();
    expect(text()).toContain('Not refreshed yet');
    expect(fixture.nativeElement.querySelector('[data-testid="overall-status"]')).toBeNull();
  });

  it('does not request the detail panels while the overview is failing and resumes when it recovers', async () => {
    service.overview.mockReturnValue(throwError(() => new Error('down')));
    await create();
    expect(service.workers).not.toHaveBeenCalled();
    expect(service.jobs).not.toHaveBeenCalled();
    expect(service.incidents).not.toHaveBeenCalled();
    field('refreshAll')();
    expect(service.overview).toHaveBeenCalledTimes(2);
    expect(service.workers).not.toHaveBeenCalled();
    service.overview.mockReturnValue(of(overview()));
    field('refreshAll')();
    expect(service.workers).toHaveBeenCalledTimes(1);
    expect(service.incidents).toHaveBeenCalledTimes(1);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('backs off exponentially while the overview keeps failing and resets after a success', async () => {
    service.overview.mockReturnValue(throwError(() => new Error('down')));
    await create();
    const base = Number(field('refreshSeconds')()) * 1000;
    expect(field('intervalMs')()).toBe(base * 2);
    field('refreshAll')();
    expect(field('intervalMs')()).toBe(base * 4);
    for (let i = 0; i < 6; i++) field('refreshAll')();
    expect(field('intervalMs')()).toBe(120_000);
    service.overview.mockReturnValue(of(overview()));
    field('refreshAll')();
    // the recovered overview carries its own 15 s hint
    expect(field('intervalMs')()).toBe(15_000);
  });

  it('does not auto-refresh before the backed-off interval has elapsed', async () => {
    service.overview.mockReturnValue(throwError(() => new Error('down')));
    await create();
    const base = Date.now();
    const clock = vi.spyOn(Date, 'now');
    clock.mockReturnValue(base + 20_000);
    field('tick')();
    expect(service.overview).toHaveBeenCalledTimes(1);
    clock.mockReturnValue(base + 41_000);
    field('tick')();
    expect(service.overview).toHaveBeenCalledTimes(2);
  });

  it('refreshes on demand and blocks overlapping refreshes', async () => {
    const pending = new Subject<OpsOverview>();
    await create();
    service.overview.mockReturnValue(pending);
    const button = fixture.nativeElement.querySelector('[data-testid="refresh"]') as HTMLButtonElement;
    button.click();
    fixture.detectChanges();
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Refreshing');
    field('refreshAll')();
    expect(service.overview).toHaveBeenCalledTimes(2);
    pending.next(overview());
    pending.complete();
    fixture.detectChanges();
    expect(service.workers).toHaveBeenCalledTimes(2);
  });

  it('auto-refreshes on the hinted cadence but never while the tab is hidden', async () => {
    await create();
    const base = Date.now();
    const clock = vi.spyOn(Date, 'now');
    clock.mockReturnValue(base + 5_000);
    field('tick')();
    expect(service.overview).toHaveBeenCalledTimes(1);
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    clock.mockReturnValue(base + 60_000);
    field('tick')();
    expect(service.overview).toHaveBeenCalledTimes(1);
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    field('tick')();
    expect(service.overview).toHaveBeenCalledTimes(2);
  });

  it('refreshes promptly when the tab becomes visible again', async () => {
    await create();
    const base = Date.now();
    vi.spyOn(Date, 'now').mockReturnValue(base + 120_000);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(service.overview).toHaveBeenCalledTimes(2);
  });

  it('stops auto-refreshing when switched off and resumes when switched on', async () => {
    await create();
    const base = Date.now();
    vi.spyOn(Date, 'now').mockReturnValue(base + 60_000);
    field('toggleAutoRefresh')(false);
    field('tick')();
    expect(service.overview).toHaveBeenCalledTimes(1);
    field('toggleAutoRefresh')(true);
    expect(service.overview).toHaveBeenCalledTimes(2);
  });

  it('clamps the refresh interval to 15-30 seconds', async () => {
    service.overview.mockReturnValue(of(overview({ refreshHintSeconds: 2 })));
    await create();
    expect(field('refreshSeconds')()).toBe(15);
    service.overview.mockReturnValue(of(overview({ refreshHintSeconds: 600 })));
    field('toggleAutoRefresh')(true);
    (component as unknown as { loading: { set(v: boolean): void } }).loading.set(false);
    field('refreshAll')();
    expect(field('refreshSeconds')()).toBe(30);
  });

  it('formats durations compactly', async () => {
    await create();
    const d = field('duration');
    expect(d(null)).toBe('—');
    expect(d(45)).toBe('45 s');
    expect(d(300)).toBe('5 min');
    expect(d(3600)).toBe('1 h');
    expect(d(4500)).toBe('1 h 15 min');
    expect(d(3 * 86400)).toBe('3 d');
  });

  it('exposes deep links only for categories that have a safe in-app destination', async () => {
    await create();
    const link = field('incidentLink');
    expect(link(incident({ category: 'DEPENDENCY' }))).toBeNull();
    expect(link(incident({ category: 'SCHEDULER' }))).toBeNull();
    expect(link(incident({ category: 'JOBS' }))).toBe('/jobs');
    expect(link(incident({ category: 'AUTOMATION' }))).toBe('/robots');
  });

  it('never renders URLs, tokens or storage keys from the data it receives', async () => {
    await create();
    expect(text()).not.toMatch(/https?:\/\//);
    expect(text()).not.toMatch(/X-Amz|Signature|Bearer/i);
  });
});
