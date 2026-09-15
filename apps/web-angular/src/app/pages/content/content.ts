import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EMPTY, Subscription, catchError, finalize, interval, startWith, switchMap } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { HighlightAnalysisSummary, HighlightCandidateSummary, MediaAssetSummary, MediaTranscriptSummary } from '../../core/assets/asset.models';

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
  protected readonly verticalBusy = signal<Record<string, boolean>>({});
  protected readonly verticalErrors = signal<Record<string, string | null>>({});
  protected readonly highlightAnalyses = signal<Record<string, HighlightAnalysisSummary | null>>({});
  protected readonly highlightBusy = signal<Record<string, boolean>>({});
  protected readonly highlightErrors = signal<Record<string, string | null>>({});
  protected readonly candidateClipBusy = signal<Record<string, boolean>>({});
  protected readonly candidateClipErrors = signal<Record<string, string | null>>({});
  protected readonly transcripts = signal<Record<string, MediaTranscriptSummary | null>>({});
  protected readonly transcriptBusy = signal<Record<string, boolean>>({});
  protected readonly transcriptErrors = signal<Record<string, string | null>>({});

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
        this.refreshHighlightAnalyses(assets);
        this.refreshTranscripts(assets);
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
    if (!asset.parentAssetId) {
      return asset.sourceType === 'DERIVED' ? 'Derived asset' : 'Original asset';
    }
    if (asset.derivationType === 'SOCIAL_VERTICAL') {
      return `Vertical of ${asset.parentAssetId.slice(0, 8)}`;
    }
    if (asset.derivationType !== 'CLIP') {
      return `Derived from ${asset.parentAssetId.slice(0, 8)}`;
    }
    return `Clip of ${asset.parentAssetId.slice(0, 8)}`;
  }

  protected canCreateClip(asset: MediaAssetSummary): boolean {
    return asset.status === 'READY' && asset.inspectionStatus === 'INSPECTED';
  }

  protected canCreateSocialVertical(asset: MediaAssetSummary): boolean {
    return asset.status === 'READY' && asset.inspectionStatus === 'INSPECTED' && asset.hasVideo === true;
  }

  protected canAnalyzeHighlights(asset: MediaAssetSummary): boolean {
    return this.canCreateSocialVertical(asset) && asset.durationMs !== null && asset.durationMs > 0;
  }

  protected canTranscribe(asset: MediaAssetSummary): boolean {
    return asset.status === 'READY' && asset.inspectionStatus === 'INSPECTED' && asset.hasAudio === true && asset.durationMs !== null && asset.durationMs > 0;
  }

  protected highlightAnalysis(asset: MediaAssetSummary): HighlightAnalysisSummary | null {
    return this.highlightAnalyses()[asset.id] ?? null;
  }

  protected analysisLabel(analysis: HighlightAnalysisSummary): string {
    switch (analysis.status) {
      case 'PENDING':
        return 'Pending';
      case 'RUNNING':
        return 'Analyzing';
      case 'SUCCEEDED':
        return 'Completed';
      case 'FAILED':
        return 'Failed';
    }
  }

  protected transcript(asset: MediaAssetSummary): MediaTranscriptSummary | null {
    return this.transcripts()[asset.id] ?? null;
  }

  protected transcriptLabel(transcript: MediaTranscriptSummary): string {
    switch (transcript.status) {
      case 'PENDING':
        return 'Pending';
      case 'RUNNING':
        return 'Transcribing';
      case 'SUCCEEDED':
        return 'Completed';
      case 'FAILED':
        return 'Failed';
    }
  }

  protected timeMs(value: number): string {
    return `${(value / 1000).toFixed(1)}s`;
  }

  protected score(value: number): string {
    return `${Math.round(value * 100)}%`;
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

  protected createSocialVertical(asset: MediaAssetSummary): void {
    this.verticalErrors.update((errors) => ({ ...errors, [asset.id]: null }));
    if (!this.canCreateSocialVertical(asset)) {
      this.verticalErrors.update((errors) => ({ ...errors, [asset.id]: 'Asset must be inspected video.' }));
      return;
    }
    this.verticalBusy.update((busy) => ({ ...busy, [asset.id]: true }));
    this.assetsService
      .createSocialVertical(asset.id)
      .pipe(finalize(() => this.verticalBusy.update((busy) => ({ ...busy, [asset.id]: false }))))
      .subscribe({
        next: (response) => {
          this.assets.set([response.asset, ...this.assets().filter((existing) => existing.id !== response.asset.id)]);
          this.loadState.set('ready');
        },
        error: () => this.verticalErrors.update((errors) => ({ ...errors, [asset.id]: 'Vertical preset could not be created.' })),
      });
  }

  protected findHighlights(asset: MediaAssetSummary): void {
    this.highlightErrors.update((errors) => ({ ...errors, [asset.id]: null }));
    if (!this.canAnalyzeHighlights(asset)) {
      this.highlightErrors.update((errors) => ({ ...errors, [asset.id]: 'Asset must be inspected video with known duration.' }));
      return;
    }
    this.highlightBusy.update((busy) => ({ ...busy, [asset.id]: true }));
    this.assetsService
      .createHighlightAnalysis(asset.id)
      .pipe(finalize(() => this.highlightBusy.update((busy) => ({ ...busy, [asset.id]: false }))))
      .subscribe({
        next: (analysis) => {
          this.highlightAnalyses.update((analyses) => ({ ...analyses, [asset.id]: analysis }));
        },
        error: () => this.highlightErrors.update((errors) => ({ ...errors, [asset.id]: 'Highlight analysis could not be started.' })),
      });
  }

  protected createClipFromCandidate(candidate: HighlightCandidateSummary): void {
    this.candidateClipErrors.update((errors) => ({ ...errors, [candidate.id]: null }));
    this.candidateClipBusy.update((busy) => ({ ...busy, [candidate.id]: true }));
    this.assetsService
      .createClipFromHighlightCandidate(candidate.id)
      .pipe(finalize(() => this.candidateClipBusy.update((busy) => ({ ...busy, [candidate.id]: false }))))
      .subscribe({
        next: (response) => {
          this.assets.set([response.asset, ...this.assets().filter((existing) => existing.id !== response.asset.id)]);
          this.loadState.set('ready');
        },
        error: () => this.candidateClipErrors.update((errors) => ({ ...errors, [candidate.id]: 'Clip could not be created from candidate.' })),
      });
  }

  protected transcribe(asset: MediaAssetSummary): void {
    this.transcriptErrors.update((errors) => ({ ...errors, [asset.id]: null }));
    if (!this.canTranscribe(asset)) {
      this.transcriptErrors.update((errors) => ({ ...errors, [asset.id]: 'Asset must be inspected media with audio.' }));
      return;
    }
    this.transcriptBusy.update((busy) => ({ ...busy, [asset.id]: true }));
    this.assetsService
      .createTranscript(asset.id)
      .pipe(finalize(() => this.transcriptBusy.update((busy) => ({ ...busy, [asset.id]: false }))))
      .subscribe({
        next: (transcript) => {
          this.transcripts.update((items) => ({ ...items, [asset.id]: transcript }));
        },
        error: () => this.transcriptErrors.update((errors) => ({ ...errors, [asset.id]: 'Transcription could not be started.' })),
      });
  }

  private refreshHighlightAnalyses(assets: MediaAssetSummary[]): void {
    for (const asset of assets) {
      if (!this.canAnalyzeHighlights(asset)) {
        continue;
      }
      const current = this.highlightAnalyses()[asset.id];
      if (current && current.status !== 'PENDING' && current.status !== 'RUNNING') {
        continue;
      }
      this.assetsService
        .listHighlightAnalyses(asset.id)
        .pipe(catchError(() => EMPTY))
        .subscribe((analyses) => {
          this.highlightAnalyses.update((currentAnalyses) => ({ ...currentAnalyses, [asset.id]: analyses[0] ?? null }));
        });
    }
  }

  private refreshTranscripts(assets: MediaAssetSummary[]): void {
    for (const asset of assets) {
      if (!this.canTranscribe(asset)) {
        continue;
      }
      const current = this.transcripts()[asset.id];
      if (current && current.status !== 'PENDING' && current.status !== 'RUNNING') {
        continue;
      }
      this.assetsService
        .listTranscripts(asset.id)
        .pipe(catchError(() => EMPTY))
        .subscribe((transcripts) => {
          this.transcripts.update((currentTranscripts) => ({ ...currentTranscripts, [asset.id]: transcripts[0] ?? null }));
        });
    }
  }
}
