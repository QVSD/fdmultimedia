import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EMPTY, Subscription, catchError, finalize, interval, startWith, switchMap } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { MediaAssetSummary } from '../../core/assets/asset.models';

type LoadState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-content',
  imports: [DatePipe, FormsModule],
  templateUrl: './content.html',
  styleUrl: './content.scss',
})
export class Content implements OnInit, OnDestroy {
  protected readonly assets = signal<MediaAssetSummary[]>([]);
  protected readonly loadState = signal<LoadState>('loading');
  protected readonly url = signal('');
  protected readonly importing = signal(false);
  protected readonly importError = signal<string | null>(null);
  protected readonly clipStarts = signal<Record<string, number>>({});
  protected readonly clipDurations = signal<Record<string, number>>({});
  protected readonly clipBusy = signal<Record<string, boolean>>({});
  protected readonly clipErrors = signal<Record<string, string | null>>({});

  private subscription?: Subscription;

  constructor(private readonly assetsService: AssetsService) {}

  ngOnInit(): void {
    this.subscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.assetsService.list().pipe(
            catchError(() => {
              this.loadState.set('error');
              return EMPTY;
            }),
          ),
        ),
      )
      .subscribe((assets) => {
        this.assets.set(assets);
        this.loadState.set('ready');
      });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  protected importMedia(): void {
    this.importError.set(null);
    const url = this.url().trim();
    if (!/^https?:\/\//i.test(url)) {
      this.importError.set('Enter a direct http or https media URL.');
      return;
    }

    this.importing.set(true);
    this.assetsService
      .importUrl(url)
      .pipe(finalize(() => this.importing.set(false)))
      .subscribe({
        next: (response) => {
          this.assets.set([response.asset, ...this.assets().filter((asset) => asset.id !== response.asset.id)]);
          this.url.set('');
          this.loadState.set('ready');
        },
        error: () => this.importError.set('Media import could not be created.'),
      });
  }

  protected shortUrl(asset: MediaAssetSummary): string {
    return asset.sourceUrl.length > 72 ? `${asset.sourceUrl.slice(0, 69)}...` : asset.sourceUrl;
  }

  protected size(asset: MediaAssetSummary): string {
    if (asset.fileSizeBytes === null) {
      return '-';
    }
    return `${(asset.fileSizeBytes / 1024 / 1024).toFixed(2)} MB`;
  }

  protected duration(asset: MediaAssetSummary): string {
    if (asset.durationMs === null) {
      return '-';
    }
    return `${Math.round(asset.durationMs / 1000)}s`;
  }

  protected resolution(asset: MediaAssetSummary): string {
    return asset.width && asset.height ? `${asset.width}x${asset.height}` : '-';
  }

  protected codecs(asset: MediaAssetSummary): string {
    const codecs = [asset.videoCodec, asset.audioCodec].filter(Boolean);
    return codecs.length ? codecs.join(' / ') : '-';
  }

  protected inspectionLabel(asset: MediaAssetSummary): string {
    switch (asset.inspectionStatus) {
      case 'PENDING':
        return 'Pending inspection';
      case 'INSPECTING':
        return 'Inspecting';
      case 'INSPECTED':
        return 'Inspected';
      case 'FAILED':
        return 'Inspection failed';
      default:
        return 'Not requested';
    }
  }

  protected lineage(asset: MediaAssetSummary): string {
    if (asset.derivationType !== 'CLIP' || !asset.parentAssetId) {
      return asset.sourceType === 'DERIVED' ? 'Derived asset' : 'Original asset';
    }
    return `Clip of ${asset.parentAssetId.slice(0, 8)}`;
  }

  protected canCreateClip(asset: MediaAssetSummary): boolean {
    return asset.status === 'READY' && asset.inspectionStatus === 'INSPECTED';
  }

  protected clipStart(asset: MediaAssetSummary): number {
    return this.clipStarts()[asset.id] ?? 0;
  }

  protected clipDuration(asset: MediaAssetSummary): number {
    return this.clipDurations()[asset.id] ?? Math.min(asset.durationMs ?? 5000, 5000);
  }

  protected setClipStart(asset: MediaAssetSummary, value: string): void {
    this.clipStarts.update((starts) => ({ ...starts, [asset.id]: Number(value) || 0 }));
  }

  protected setClipDuration(asset: MediaAssetSummary, value: string): void {
    this.clipDurations.update((durations) => ({ ...durations, [asset.id]: Number(value) || 0 }));
  }

  protected createClip(asset: MediaAssetSummary): void {
    const startMs = this.clipStart(asset);
    const durationMs = this.clipDuration(asset);
    this.clipErrors.update((errors) => ({ ...errors, [asset.id]: null }));
    if (startMs < 0 || durationMs <= 0 || (asset.durationMs !== null && startMs + durationMs > asset.durationMs)) {
      this.clipErrors.update((errors) => ({ ...errors, [asset.id]: 'Enter a valid clip range.' }));
      return;
    }
    this.clipBusy.update((busy) => ({ ...busy, [asset.id]: true }));
    this.assetsService
      .createClip(asset.id, startMs, durationMs)
      .pipe(finalize(() => this.clipBusy.update((busy) => ({ ...busy, [asset.id]: false }))))
      .subscribe({
        next: (response) => {
          this.assets.set([response.asset, ...this.assets().filter((existing) => existing.id !== response.asset.id)]);
          this.loadState.set('ready');
        },
        error: () => this.clipErrors.update((errors) => ({ ...errors, [asset.id]: 'Clip could not be created.' })),
      });
  }
}
