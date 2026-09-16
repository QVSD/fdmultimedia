import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { Subscription, interval, startWith, switchMap } from 'rxjs';

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
  protected readonly onlineCount = computed(() => this.workers().filter((worker) => worker.status === 'ONLINE').length);

  private subscription?: Subscription;

  constructor(private readonly workersService: WorkersService) {}

  ngOnInit(): void {
    this.subscription = interval(10_000)
      .pipe(
        startWith(0),
        switchMap(() => this.workersService.list()),
      )
      .subscribe({
        next: (workers) => {
          this.workers.set(workers);
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
      return 'Unknown';
    }
    return activeJobs === 1 ? '1 active' : `${activeJobs} active`;
  }

  protected capabilities(worker: WorkerSummary): string[] {
    const jobTypes = worker.supportedJobTypes ?? [];
    const analyzers = worker.supportedHighlightAnalyzers ?? [];
    return [...jobTypes, ...analyzers.map((analyzer) => `Highlights: ${analyzer}`)];
  }
}
