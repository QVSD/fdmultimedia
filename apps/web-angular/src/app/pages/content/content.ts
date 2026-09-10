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
}
