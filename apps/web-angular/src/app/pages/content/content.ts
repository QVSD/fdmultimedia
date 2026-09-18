import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EMPTY, Subscription, catchError, finalize, interval, startWith, switchMap } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { HighlightAnalysisSummary, HighlightCandidateSummary, MediaAssetSummary, MediaTranscriptSummary } from '../../core/assets/asset.models';
import { PublishingService } from '../../core/publishing/publishing.service';
import { PublicationSummary } from '../../core/publishing/publishing.models';
import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import { SocialAccountSummary } from '../../core/social-accounts/social-account.models';

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
  protected readonly expandedAssets = signal<Record<string, boolean>>({});
  protected readonly socialAccounts = signal<SocialAccountSummary[]>([]);
  protected readonly publications = signal<Record<string, PublicationSummary[]>>({});
  protected readonly publishAccountId = signal<Record<string, string>>({});
  protected readonly publishCaptions = signal<Record<string, string>>({});
  protected readonly publishBusy = signal<Record<string, boolean>>({});
  protected readonly publishErrors = signal<Record<string, string | null>>({});

  private subscription?: Subscription;

  constructor(
    private readonly assetsService: AssetsService,
    private readonly publishingService: PublishingService,
    private readonly socialAccountsService: SocialAccountsService,
  ) {}

  ngOnInit(): void {
    this.socialAccountsService
      .list()
      .pipe(catchError(() => EMPTY))
      .subscribe((accounts) => this.socialAccounts.set(accounts));

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
        this.refreshPublications(assets);
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

  protected assetTitle(asset: MediaAssetSummary): string {
    return asset.originalFilename || (asset.derivationType === 'ORIGINAL' ? 'Imported media' : this.derivationLabel(asset));
  }

  protected parentTitle(asset: MediaAssetSummary): string {
    if (!asset.parentAssetId) {
      return '';
    }
    const parent = this.assets().find((item) => item.id === asset.parentAssetId);
    return parent ? this.assetTitle(parent) : asset.parentAssetId.slice(0, 8);
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

  protected inspectionStatusLabel(asset: MediaAssetSummary): string {
    switch (asset.inspectionStatus) {
      case 'PENDING':
        return 'Waiting for inspection';
      case 'INSPECTING':
        return 'Inspecting';
      case 'INSPECTED':
        return 'Inspected';
      case 'FAILED':
        return 'Inspection failed';
      default:
        return 'Not inspected';
    }
  }

  protected analyzerLabel(analyzerType: string): string {
    if (analyzerType === 'TRANSCRIPT_SEMANTIC_V1') {
      return 'Transcript AI';
    }
    if (analyzerType === 'DETERMINISTIC_V1') {
      return 'Baseline';
    }
    return analyzerType;
  }

  protected transcriptProviderLabel(transcript: MediaTranscriptSummary): string {
    const language = transcript.detectedLanguage ? transcript.detectedLanguage.toUpperCase() : 'Language unknown';
    const provider = transcript.provider.includes('WHISPER') ? 'Local Whisper' : transcript.provider;
    return `${language} · ${provider}`;
  }

  protected mediaSummary(asset: MediaAssetSummary): string {
    return [
      this.duration(asset),
      this.resolution(asset),
      this.codecs(asset),
    ].filter((value) => value !== '-').join(' · ') || 'Metadata pending';
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
      return `Vertical from ${this.parentTitle(asset)}`;
    }
    if (asset.derivationType !== 'CLIP') {
      return `Derived from ${this.parentTitle(asset)}`;
    }
    return `Clip from ${this.parentTitle(asset)}`;
  }

  protected isExpanded(asset: MediaAssetSummary): boolean {
    return this.expandedAssets()[asset.id] ?? false;
  }

  protected toggleAsset(asset: MediaAssetSummary): void {
    this.expandedAssets.update((items) => ({ ...items, [asset.id]: !(items[asset.id] ?? false) }));
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

  protected canAnalyzeSemanticHighlights(asset: MediaAssetSummary): boolean {
    const transcript = this.transcript(asset);
    return this.canAnalyzeHighlights(asset) && transcript?.status === 'SUCCEEDED' && (transcript.segments?.length ?? 0) > 0;
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
    this.startHighlightAnalysis(asset, 'DETERMINISTIC_V1', 'Asset must be inspected video with known duration.', 'Highlight analysis could not be started.');
  }

  protected findSemanticHighlights(asset: MediaAssetSummary): void {
    this.startHighlightAnalysis(asset, 'TRANSCRIPT_SEMANTIC_V1', 'A completed transcript is required for semantic highlights.', 'Semantic highlight analysis could not be started.');
  }

  private startHighlightAnalysis(asset: MediaAssetSummary, analyzer: string, validationMessage: string, errorMessage: string): void {
    this.highlightErrors.update((errors) => ({ ...errors, [asset.id]: null }));
    if (analyzer === 'TRANSCRIPT_SEMANTIC_V1' ? !this.canAnalyzeSemanticHighlights(asset) : !this.canAnalyzeHighlights(asset)) {
      this.highlightErrors.update((errors) => ({ ...errors, [asset.id]: validationMessage }));
      return;
    }
    this.highlightBusy.update((busy) => ({ ...busy, [asset.id]: true }));
    this.assetsService
      .createHighlightAnalysis(asset.id, analyzer)
      .pipe(finalize(() => this.highlightBusy.update((busy) => ({ ...busy, [asset.id]: false }))))
      .subscribe({
        next: (analysis) => {
          this.highlightAnalyses.update((analyses) => ({ ...analyses, [asset.id]: analysis }));
        },
        error: () => this.highlightErrors.update((errors) => ({ ...errors, [asset.id]: errorMessage })),
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

  protected canPublish(asset: MediaAssetSummary): boolean {
    return asset.status === 'READY' && asset.inspectionStatus === 'INSPECTED' && asset.hasVideo === true;
  }

  protected testSocialAccounts(): SocialAccountSummary[] {
    return this.socialAccounts().filter((account) => account.status === 'ACTIVE');
  }

  protected publicationsFor(asset: MediaAssetSummary): PublicationSummary[] {
    return this.publications()[asset.id] ?? [];
  }

  protected publishAccountFor(asset: MediaAssetSummary): string {
    return this.publishAccountId()[asset.id] ?? this.testSocialAccounts()[0]?.id ?? '';
  }

  protected setPublishAccount(asset: MediaAssetSummary, value: string): void {
    this.publishAccountId.update((ids) => ({ ...ids, [asset.id]: value }));
  }

  protected publishCaptionFor(asset: MediaAssetSummary): string {
    return this.publishCaptions()[asset.id] ?? '';
  }

  protected setPublishCaption(asset: MediaAssetSummary, value: string): void {
    this.publishCaptions.update((captions) => ({ ...captions, [asset.id]: value }));
  }

  protected publicationStatusLabel(status: PublicationSummary['status']): string {
    switch (status) {
      case 'PENDING':
        return 'Queued';
      case 'PUBLISHING':
        return 'Publishing';
      case 'PUBLISHED':
        return 'Published';
      case 'FAILED':
        return 'Failed';
      case 'CANCELLED':
        return 'Cancelled';
    }
  }

  protected publish(asset: MediaAssetSummary): void {
    this.publishErrors.update((errors) => ({ ...errors, [asset.id]: null }));
    const socialAccountId = this.publishAccountFor(asset);
    if (!this.canPublish(asset)) {
      this.publishErrors.update((errors) => ({ ...errors, [asset.id]: 'Asset must be inspected video.' }));
      return;
    }
    if (!socialAccountId) {
      this.publishErrors.update((errors) => ({ ...errors, [asset.id]: 'Add a TEST social account first.' }));
      return;
    }
    const caption = this.publishCaptionFor(asset).trim();
    this.publishBusy.update((busy) => ({ ...busy, [asset.id]: true }));
    this.publishingService
      .createPublication(asset.id, socialAccountId, caption || null)
      .pipe(finalize(() => this.publishBusy.update((busy) => ({ ...busy, [asset.id]: false }))))
      .subscribe({
        next: (publication) => {
          this.publications.update((items) => ({
            ...items,
            [asset.id]: [publication, ...(items[asset.id] ?? [])],
          }));
          this.publishCaptions.update((captions) => ({ ...captions, [asset.id]: '' }));
        },
        error: () => this.publishErrors.update((errors) => ({ ...errors, [asset.id]: 'Publication could not be started.' })),
      });
  }

  private refreshPublications(assets: MediaAssetSummary[]): void {
    for (const asset of assets) {
      if (!this.canPublish(asset)) {
        continue;
      }
      const current = this.publications()[asset.id] ?? [];
      const hasInFlight = current.some((publication) => publication.status === 'PENDING' || publication.status === 'PUBLISHING');
      if (current.length > 0 && !hasInFlight) {
        continue;
      }
      this.publishingService
        .listForAsset(asset.id)
        .pipe(catchError(() => EMPTY))
        .subscribe((publications) => {
          this.publications.update((items) => ({ ...items, [asset.id]: publications }));
        });
    }
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
