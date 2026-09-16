import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, signal } from '@angular/core';

import { AssetsService } from '../../core/assets/assets.service';
import { MediaAssetSummary } from '../../core/assets/asset.models';
import { HealthService } from '../../core/health.service';
import { JobsService } from '../../core/jobs/jobs.service';
import { JobSummary } from '../../core/jobs/job.models';
import { WorkersService } from '../../core/workers/workers.service';
import { WorkerSummary } from '../../core/workers/worker.models';

type BackendStatus = 'checking' | 'online' | 'offline';
type PanelState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-overview',
  imports: [DatePipe],
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class Overview implements OnInit {
  protected readonly backendStatus = signal<BackendStatus>('checking');
  protected readonly assets = signal<MediaAssetSummary[]>([]);
  protected readonly workers = signal<WorkerSummary[]>([]);
  protected readonly jobs = signal<JobSummary[]>([]);
  protected readonly assetsState = signal<PanelState>('loading');
  protected readonly workersState = signal<PanelState>('loading');
  protected readonly jobsState = signal<PanelState>('loading');

  protected readonly onlineWorkers = computed(() => this.workers().filter((worker) => worker.status === 'ONLINE').length);
  protected readonly readyAssets = computed(() => this.assets().filter((asset) => asset.status === 'READY').length);
  protected readonly processingAssets = computed(() =>
    this.assets().filter((asset) => asset.status === 'PENDING' || asset.status === 'IMPORTING' || asset.status === 'PROCESSING').length,
  );
  protected readonly failedAssets = computed(() => this.assets().filter((asset) => asset.status === 'FAILED' || asset.inspectionStatus === 'FAILED').length);
  protected readonly activeJobs = computed(() =>
    this.jobs().filter((job) => job.status === 'QUEUED' || job.status === 'ASSIGNED' || job.status === 'RUNNING').length,
  );
  protected readonly failedJobs = computed(() => this.jobs().filter((job) => job.status === 'FAILED').length);
  protected readonly recentAssets = computed(() => this.assets().slice(0, 5));

  constructor(
    private readonly healthService: HealthService,
    private readonly assetsService: AssetsService,
    private readonly workersService: WorkersService,
    private readonly jobsService: JobsService,
  ) {}

  ngOnInit(): void {
    this.healthService.check().subscribe({
      next: () => this.backendStatus.set('online'),
      error: () => this.backendStatus.set('offline'),
    });
    this.assetsService.list().subscribe({
      next: (assets) => {
        this.assets.set(assets);
        this.assetsState.set('ready');
      },
      error: () => this.assetsState.set('error'),
    });
    this.workersService.list().subscribe({
      next: (workers) => {
        this.workers.set(workers);
        this.workersState.set('ready');
      },
      error: () => this.workersState.set('error'),
    });
    this.jobsService.list().subscribe({
      next: (jobs) => {
        this.jobs.set(jobs);
        this.jobsState.set('ready');
      },
      error: () => this.jobsState.set('error'),
    });
  }

  protected systemLabel(): string {
    if (this.backendStatus() === 'checking') {
      return 'Checking';
    }
    return this.backendStatus() === 'online' ? 'Online' : 'Offline';
  }

  protected attentionCount(): number {
    return this.failedAssets() + this.failedJobs();
  }

  protected assetTitle(asset: MediaAssetSummary): string {
    return asset.originalFilename || (asset.derivationType === 'ORIGINAL' ? 'Imported media' : this.derivationLabel(asset));
  }

  protected derivationLabel(asset: MediaAssetSummary): string {
    if (asset.derivationType === 'CLIP') {
      return 'Clip';
    }
    if (asset.derivationType === 'SOCIAL_VERTICAL') {
      return 'Vertical';
    }
    return 'Original';
  }

  protected statusLabel(asset: MediaAssetSummary): string {
    switch (asset.status) {
      case 'PENDING':
        return 'Waiting';
      case 'IMPORTING':
      case 'PROCESSING':
        return 'Processing';
      case 'READY':
        return 'Ready';
      case 'FAILED':
        return 'Failed';
    }
  }
}
