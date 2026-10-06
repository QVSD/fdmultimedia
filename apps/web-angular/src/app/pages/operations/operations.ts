import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, Subscription } from 'rxjs';

import {
  ComponentStatus,
  OpsIncident,
  OpsIncidentsView,
  OpsJobsView,
  OpsOverview,
  OpsPublishingView,
  OpsSchedulersView,
  OpsSeverity,
  OpsWorkersView,
  OverallStatus,
  SchedulerState,
  WorkerState,
} from '../../core/operations/operations.models';
import { environment } from '../../../environments/environment';
import { OperationsService } from '../../core/operations/operations.service';

export interface Panel<T> {
  data: T | null;
  error: boolean;
  loadedAt: number | null;
}

const emptyPanel = <T>(): Panel<T> => ({ data: null, error: false, loadedAt: null });

export const DEFAULT_REFRESH_SECONDS = 20;
const MIN_REFRESH_SECONDS = 15;
const MAX_REFRESH_SECONDS = 30;
const MAX_BACKOFF_SECONDS = 120;

const STATUS_LABELS: Record<ComponentStatus | OverallStatus, string> = {
  HEALTHY: 'Healthy',
  DEGRADED: 'Degraded',
  UNAVAILABLE: 'Unavailable',
  UNKNOWN: 'Unknown',
  ACTION_REQUIRED: 'Action required',
};

const STATUS_ICONS: Record<ComponentStatus | OverallStatus, string> = {
  HEALTHY: '✓',
  DEGRADED: '▲',
  UNAVAILABLE: '✕',
  UNKNOWN: '?',
  ACTION_REQUIRED: '✕',
};

const SEVERITY_LABELS: Record<OpsSeverity, string> = { CRITICAL: 'Critical', WARNING: 'Warning', INFO: 'Info' };
const SEVERITY_ICONS: Record<OpsSeverity, string> = { CRITICAL: '✕', WARNING: '▲', INFO: 'i' };

@Component({
  selector: 'app-operations',
  imports: [DatePipe, RouterLink],
  templateUrl: './operations.html',
  styleUrl: './operations.scss',
})
export class Operations implements OnInit, OnDestroy {
  private readonly operations = inject(OperationsService);

  protected readonly overview = signal<Panel<OpsOverview>>(emptyPanel());
  protected readonly workers = signal<Panel<OpsWorkersView>>(emptyPanel());
  protected readonly failedJobs = signal<Panel<OpsJobsView>>(emptyPanel());
  protected readonly schedulers = signal<Panel<OpsSchedulersView>>(emptyPanel());
  protected readonly publishing = signal<Panel<OpsPublishingView>>(emptyPanel());
  protected readonly incidents = signal<Panel<OpsIncidentsView>>(emptyPanel());

  protected readonly webVersion = environment.version;

  /** Distinct Worker release versions among registered Workers that were seen recently (offline history is ignored). */
  protected readonly workerVersions = computed(() => {
    const rows = this.workers().data?.workers.items ?? [];
    return [...new Set(rows.filter((w) => w.state !== 'OFFLINE' && w.agentVersion).map((w) => (w.agentVersion as string).replace(/^fdm-worker-java\//, '')))];
  });

  /** True when every reporting component is built from the same release as this page. */
  protected readonly versionsConsistent = computed(() => {
    const api = this.overview().data?.api.version;
    if (!api) return true;
    return api === this.webVersion && this.workerVersions().every((v) => v === api);
  });

  protected readonly loading = signal(false);
  protected readonly autoRefresh = signal(true);
  protected readonly nowMs = signal(Date.now());
  protected readonly selectedIncidentId = signal<string | null>(null);
  protected readonly acknowledging = signal<string | null>(null);
  protected readonly acknowledgeError = signal<string | null>(null);

  protected readonly refreshSeconds = computed(() => {
    const hint = this.overview().data?.refreshHintSeconds ?? DEFAULT_REFRESH_SECONDS;
    return Math.min(MAX_REFRESH_SECONDS, Math.max(MIN_REFRESH_SECONDS, hint));
  });

  protected readonly selectedIncident = computed<OpsIncident | null>(() => {
    const items = this.incidents().data?.incidents.items ?? this.overview().data?.topIncidents ?? [];
    const id = this.selectedIncidentId();
    return items.find((incident) => incident.id === id) ?? items[0] ?? null;
  });

  private refreshTimer?: ReturnType<typeof setInterval>;
  private clockTimer?: ReturnType<typeof setInterval>;
  private lastAttemptMs = 0;
  private failures = 0;
  private readonly requests = new Subscription();
  private readonly onVisibility = (): void => this.handleVisibility();

  ngOnInit(): void {
    this.refreshAll();
    this.refreshTimer = setInterval(() => this.tick(), 5000);
    this.clockTimer = setInterval(() => {
      if (this.visible()) this.nowMs.set(Date.now());
    }, 1000);
    document.addEventListener('visibilitychange', this.onVisibility);
  }

  ngOnDestroy(): void {
    clearInterval(this.refreshTimer);
    clearInterval(this.clockTimer);
    document.removeEventListener('visibilitychange', this.onVisibility);
    this.requests.unsubscribe();
  }

  /**
   * Auto-refresh never runs while the tab is hidden, never overlaps a request still in flight, and backs off exponentially
   * (up to {@link MAX_BACKOFF_SECONDS}) while the overview itself keeps failing, so an outage cannot turn into a request storm.
   */
  protected tick(): void {
    if (!this.autoRefresh() || !this.visible() || this.loading()) return;
    if (Date.now() - this.lastAttemptMs >= this.intervalMs()) this.refreshAll();
  }

  protected intervalMs(): number {
    const base = this.refreshSeconds();
    const factor = Math.min(MAX_BACKOFF_SECONDS / base, 2 ** this.failures);
    return base * 1000 * Math.max(1, factor);
  }

  private handleVisibility(): void {
    if (this.visible()) {
      this.nowMs.set(Date.now());
      this.tick();
    }
  }

  private visible(): boolean {
    return typeof document === 'undefined' || document.visibilityState !== 'hidden';
  }

  protected toggleAutoRefresh(enabled: boolean): void {
    this.autoRefresh.set(enabled);
    if (enabled) this.tick();
  }

  /**
   * While the overview is failing only the overview is retried; the five detail panels are requested again as soon as it recovers.
   */
  protected refreshAll(): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.lastAttemptMs = Date.now();
    this.requests.add(
      this.operations.overview().subscribe({
        next: (data) => {
          this.overview.set({ data, error: false, loadedAt: Date.now() });
          this.failures = 0;
          this.loadDetails(() => this.loading.set(false));
        },
        error: () => {
          this.overview.update((previous) => ({ ...previous, error: true }));
          this.failures += 1;
          this.loading.set(false);
        },
      }),
    );
  }

  private loadDetails(finished: () => void): void {
    let pending = 5;
    const done = (): void => {
      pending -= 1;
      if (pending === 0) finished();
    };
    this.load(this.operations.workers(0, 10), this.workers, done);
    this.load(this.operations.jobs(0, 8, 'FAILED'), this.failedJobs, done);
    this.load(this.operations.schedulers(), this.schedulers, done);
    this.load(this.operations.publishing(0, 8), this.publishing, done);
    this.load(this.operations.incidents('ACTIVE', 0, 25), this.incidents, done);
  }

  /** Each panel fails independently and keeps its last good data, so one broken source never blanks the page. */
  private load<T>(request: Observable<T>, target: { update: (fn: (p: Panel<T>) => Panel<T>) => void }, done: () => void): void {
    this.requests.add(
      request.subscribe({
        next: (data) => {
          target.update(() => ({ data, error: false, loadedAt: Date.now() }));
          done();
        },
        error: () => {
          target.update((previous) => ({ ...previous, error: true }));
          done();
        },
      }),
    );
  }

  protected acknowledge(incident: OpsIncident): void {
    if (!incident.persisted || incident.acknowledged || this.acknowledging()) return;
    this.acknowledging.set(incident.id);
    this.acknowledgeError.set(null);
    this.requests.add(
      this.operations.acknowledge(incident.id).subscribe({
        next: () => {
          this.acknowledging.set(null);
          this.loading.set(false);
          this.refreshAll();
        },
        error: () => {
          this.acknowledging.set(null);
          this.acknowledgeError.set('The incident could not be acknowledged. It may already be resolved.');
        },
      }),
    );
  }

  protected selectIncident(incident: OpsIncident): void {
    this.selectedIncidentId.set(incident.id);
  }

  // ---- presentation helpers ----

  protected statusLabel(status: ComponentStatus | OverallStatus): string {
    return STATUS_LABELS[status] ?? status;
  }

  protected statusIcon(status: ComponentStatus | OverallStatus): string {
    return STATUS_ICONS[status] ?? '?';
  }

  protected statusClass(status: string): string {
    return `status--${status.toLowerCase().replace(/_/g, '-')}`;
  }

  protected severityLabel(severity: OpsSeverity): string {
    return SEVERITY_LABELS[severity];
  }

  protected severityIcon(severity: OpsSeverity): string {
    return SEVERITY_ICONS[severity];
  }

  protected workerStateClass(state: WorkerState): string {
    return state === 'ONLINE' ? 'status--healthy' : state === 'STALE' ? 'status--degraded' : 'status--unavailable';
  }

  protected schedulerStateClass(state: SchedulerState): string {
    return state === 'FAILED' ? 'status--unavailable' : state === 'DEGRADED' ? 'status--degraded' : state === 'UNKNOWN' ? 'status--unknown' : 'status--healthy';
  }

  protected duration(seconds: number | null | undefined): string {
    if (seconds === null || seconds === undefined) return '—';
    if (seconds < 60) return `${Math.round(seconds)} s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    if (hours < 48) return minutes % 60 === 0 ? `${hours} h` : `${hours} h ${minutes % 60} min`;
    return `${Math.floor(hours / 24)} d`;
  }

  protected ago(iso: string | null | undefined): string {
    if (!iso) return 'never';
    const seconds = Math.max(0, Math.round((this.nowMs() - Date.parse(iso)) / 1000));
    return `${this.duration(seconds)} ago`;
  }

  protected agoMs(ms: number | null): string {
    if (ms === null) return 'never';
    return `${this.duration(Math.max(0, Math.round((this.nowMs() - ms) / 1000)))} ago`;
  }

  protected stalenessNote(panel: Panel<unknown>): string | null {
    return panel.error && panel.loadedAt !== null ? `Showing data from ${this.agoMs(panel.loadedAt)}.` : null;
  }

  protected cadence(seconds: number): string {
    return seconds === 0 ? 'on demand' : `every ${this.duration(seconds)}`;
  }

  protected incidentLink(incident: OpsIncident): string | null {
    switch (incident.category) {
      case 'WORKERS':
        return '/compute';
      case 'JOBS':
        return '/jobs';
      case 'PUBLISHING':
        return '/content';
      case 'AUTOMATION':
        return '/robots';
      default:
        return null;
    }
  }

  protected linkLabel(incident: OpsIncident): string {
    switch (incident.category) {
      case 'WORKERS':
        return 'Open Compute';
      case 'JOBS':
        return 'Open Jobs';
      case 'PUBLISHING':
        return 'Open Content';
      case 'AUTOMATION':
        return 'Open Robots';
      default:
        return '';
    }
  }
}
