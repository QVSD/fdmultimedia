import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { EMPTY, Subscription, catchError, forkJoin, interval, startWith, switchMap } from 'rxjs';

import { SchedulingDecisionSummary, SchedulingOverview, WorkerPerformanceSummary } from '../../core/scheduling/scheduling.models';
import { SchedulingService } from '../../core/scheduling/scheduling.service';
import { WorkerSummary } from '../../core/workers/worker.models';
import { WorkersService } from '../../core/workers/workers.service';

type LoadState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-compute',
  imports: [DatePipe],
  templateUrl: './compute.html',
  styleUrl: './compute.scss',
})
export class Compute implements OnInit, OnDestroy {
  protected readonly workers = signal<WorkerSummary[]>([]);
  protected readonly loadState = signal<LoadState>('loading');
  protected readonly overview = signal<SchedulingOverview | null>(null);
  protected readonly workerPerformance = signal<WorkerPerformanceSummary[]>([]);
  protected readonly decisions = signal<SchedulingDecisionSummary[]>([]);
  protected readonly onlineCount = computed(() => this.workers().filter((worker) => worker.status === 'ONLINE').length);

  private subscription?: Subscription;

  constructor(
    private readonly workersService: WorkersService,
    private readonly schedulingService: SchedulingService,
  ) {}

  ngOnInit(): void {
    this.subscription = interval(10_000)
      .pipe(
        startWith(0),
        switchMap(() =>
          forkJoin({
            workers: this.workersService.list(),
            overview: this.schedulingService.overview(),
            performance: this.schedulingService.workers(),
            decisions: this.schedulingService.decisions(),
          }).pipe(
            catchError(() => {
              this.loadState.set('error');
              return EMPTY;
            }),
          ),
        ),
      )
      .subscribe({
        next: ({ workers, overview, performance, decisions }) => {
          this.workers.set(workers);
          this.overview.set(overview);
          this.workerPerformance.set(performance);
          this.decisions.set(decisions);
          this.loadState.set('ready');
        },
        error: () => this.loadState.set('error'),
      });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  protected formatBytes(bytes: number | null): string {
    if (bytes === null) {
      return 'Unknown';
    }
    const gib = bytes / 1024 / 1024 / 1024;
    return `${gib.toFixed(gib >= 10 ? 0 : 1)} GiB`;
  }

  protected formatPercent(value: number | null | undefined): string {
    if (value === null || value === undefined) {
      return 'Unavailable';
    }
    return `${Math.round(value * 100)}%`;
  }

  protected telemetryLabel(worker: WorkerSummary): string {
    if (!worker.telemetry?.lastTelemetryAt) {
      return 'Telemetry unavailable';
    }
    return worker.telemetry.fresh ? 'Telemetry current' : 'Telemetry stale';
  }

  protected activeJobsLabel(worker: WorkerSummary): string {
    const activeJobs = worker.telemetry?.activeJobs;
    if (activeJobs === null || activeJobs === undefined) {
      return `Unknown / ${worker.maxActiveJobs}`;
    }
    return `${activeJobs} / ${worker.maxActiveJobs} active`;
  }

  protected schedulingLabel(worker: WorkerSummary): string {
    switch (worker.schedulingState) {
      case 'AVAILABLE':
        return 'Available for claims';
      case 'AT_CAPACITY':
        return 'At capacity';
      case 'MEMORY_PRESSURE':
        return 'Memory pressure';
      case 'TELEMETRY_STALE':
        return 'Telemetry stale';
      case 'TELEMETRY_UNAVAILABLE':
        return 'Telemetry unavailable';
      default:
        return worker.schedulingState;
    }
  }

  protected capabilities(worker: WorkerSummary): string[] {
    const jobTypes = worker.supportedJobTypes ?? [];
    const analyzers = worker.supportedHighlightAnalyzers ?? [];
    return [...jobTypes, ...analyzers.map((analyzer) => `Highlights: ${analyzer}`)];
  }

  protected performanceFor(workerId: string): WorkerPerformanceSummary[] {
    return this.workerPerformance().filter((metric) => metric.workerId === workerId);
  }

  protected formatDuration(value: number | null | undefined): string {
    if (value === null || value === undefined) return '-';
    if (value < 1000) return `${Math.round(value)} ms`;
    if (value < 60_000) return `${(value / 1000).toFixed(1)} s`;
    const minutes = Math.floor(value / 60_000);
    return `${minutes}m ${Math.round((value % 60_000) / 1000)}s`;
  }
}
