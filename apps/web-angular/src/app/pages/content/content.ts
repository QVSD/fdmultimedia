import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EMPTY, Subscription, catchError, finalize, interval, startWith, switchMap } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { HighlightAnalysisSummary, HighlightCandidateSummary, MediaAssetSummary, MediaTranscriptSummary } from '../../core/assets/asset.models';
import { PublishingService } from '../../core/publishing/publishing.service';
import { PublicationSummary } from '../../core/publishing/publishing.models';
import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import { SocialAccountSummary, TikTokCreatorInfo } from '../../core/social-accounts/social-account.models';
import { ContentDraftsService } from '../../core/content-drafts/content-drafts.service';
import { ContentDraftStatus, ContentDraftSummary, ContentDraftWorkflowStage } from '../../core/content-drafts/content-draft.models';
import { PublishSchedulesService } from '../../core/publish-schedules/publish-schedules.service';
import { PublishScheduleStatus, PublishScheduleSummary } from '../../core/publish-schedules/publish-schedule.models';
import { ContentSourcesService } from '../../core/content-sources/content-sources.service';
import { ContentSourceAssetSummary, ContentSourceSummary } from '../../core/content-sources/content-source.models';
import { ContentSuggestionsService } from '../../core/content-suggestions/content-suggestions.service';
import { ContentSuggestionSummary, SuggestionLanguage, SuggestionTone } from '../../core/content-suggestions/content-suggestion.models';
import { PersonasService } from '../../core/personas/personas.service';
import { PersonaSummary } from '../../core/personas/persona.models';

type LoadState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-content',
  imports: [DatePipe, FormsModule, RouterLink],
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
  protected readonly tiktokCapabilities = signal<Record<string, TikTokCreatorInfo | null>>({});
  protected readonly tiktokPrivacy = signal<Record<string, string>>({});
  protected readonly tiktokDisableComment = signal<Record<string, boolean>>({});
  protected readonly tiktokDisableDuet = signal<Record<string, boolean>>({});
  protected readonly tiktokDisableStitch = signal<Record<string, boolean>>({});

  protected readonly activeView = signal<'assets' | 'drafts' | 'schedule' | 'sources'>('assets');

  protected readonly contentSources = signal<ContentSourceSummary[]>([]);
  protected readonly contentSourcesLoadState = signal<LoadState>('loading');
  protected readonly sourceCreateName = signal('');
  protected readonly sourceCreateDescription = signal('');
  protected readonly sourceCreateBusy = signal(false);
  protected readonly sourceCreateError = signal<string | null>(null);
  protected readonly sourcePauseResumeBusy = signal<Record<string, boolean>>({});
  protected readonly expandedSources = signal<Record<string, boolean>>({});
  protected readonly sourceAssets = signal<Record<string, ContentSourceAssetSummary[]>>({});
  protected readonly sourceAddAssetId = signal<Record<string, string>>({});
  protected readonly sourceAddBusy = signal<Record<string, boolean>>({});
  protected readonly sourceAddErrors = signal<Record<string, string | null>>({});
  protected readonly sourceRemoveBusy = signal<Record<string, boolean>>({});
  protected readonly addToSourceSelection = signal<Record<string, string>>({});
  protected readonly addToSourceBusy = signal<Record<string, boolean>>({});
  protected readonly addToSourceErrors = signal<Record<string, string | null>>({});
  protected readonly addToSourceDone = signal<Record<string, boolean>>({});

  protected readonly aiSuggestions = signal<Record<string, ContentSuggestionSummary[]>>({});
  protected readonly aiLanguage = signal<Record<string, SuggestionLanguage>>({});
  protected readonly aiTone = signal<Record<string, SuggestionTone>>({});
  protected readonly aiPersonaId = signal<Record<string, string>>({});
  protected readonly personas = signal<PersonaSummary[]>([]);
  protected readonly aiGenerateBusy = signal<Record<string, boolean>>({});
  protected readonly aiGenerateErrors = signal<Record<string, string | null>>({});
  protected readonly aiApplyBusy = signal<Record<string, boolean>>({});
  protected readonly aiApplyErrors = signal<Record<string, string | null>>({});
  protected readonly aiDiscardBusy = signal<Record<string, boolean>>({});
  private readonly aiPollers = new Map<string, Subscription>();
  protected readonly drafts = signal<ContentDraftSummary[]>([]);
  protected readonly draftsLoadState = signal<LoadState>('loading');
  protected readonly expandedDrafts = signal<Record<string, boolean>>({});
  protected readonly draftCreateBusy = signal<Record<string, boolean>>({});
  protected readonly draftCreateErrors = signal<Record<string, string | null>>({});
  protected readonly draftTitleEdits = signal<Record<string, string>>({});
  protected readonly draftCaptionEdits = signal<Record<string, string>>({});
  protected readonly draftSaveBusy = signal<Record<string, boolean>>({});
  protected readonly draftSaveErrors = signal<Record<string, string | null>>({});
  protected readonly draftPublishAccountId = signal<Record<string, string>>({});
  protected readonly draftPublishBusy = signal<Record<string, boolean>>({});
  protected readonly draftPublishErrors = signal<Record<string, string | null>>({});
  protected readonly draftRetryBusy = signal<Record<string, boolean>>({});
  protected readonly draftRetryErrors = signal<Record<string, string | null>>({});

  protected readonly draftScheduleAccountId = signal<Record<string, string>>({});
  protected readonly draftScheduleDateTime = signal<Record<string, string>>({});
  protected readonly scheduleCreateBusy = signal<Record<string, boolean>>({});
  protected readonly scheduleCreateErrors = signal<Record<string, string | null>>({});

  protected readonly schedules = signal<PublishScheduleSummary[]>([]);
  protected readonly schedulesLoadState = signal<LoadState>('loading');
  protected readonly scheduleCancelBusy = signal<Record<string, boolean>>({});
  protected readonly scheduleCancelErrors = signal<Record<string, string | null>>({});
  protected readonly rescheduleEditing = signal<Record<string, boolean>>({});
  protected readonly rescheduleDateTime = signal<Record<string, string>>({});
  protected readonly rescheduleBusy = signal<Record<string, boolean>>({});
  protected readonly rescheduleErrors = signal<Record<string, string | null>>({});

  protected readonly localTimeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  private subscription?: Subscription;
  private draftsSubscription?: Subscription;
  private schedulesSubscription?: Subscription;
  private contentSourcesSubscription?: Subscription;

  constructor(
    private readonly assetsService: AssetsService,
    private readonly publishingService: PublishingService,
    private readonly socialAccountsService: SocialAccountsService,
    private readonly contentDraftsService: ContentDraftsService,
    private readonly publishSchedulesService: PublishSchedulesService,
    private readonly contentSourcesService: ContentSourcesService,
    private readonly contentSuggestionsService: ContentSuggestionsService,
    private readonly personasService: PersonasService,
  ) {}

  ngOnInit(): void {
    this.socialAccountsService
      .list()
      .pipe(catchError(() => EMPTY))
      .subscribe((accounts) => this.socialAccounts.set(accounts));

    this.personasService
      .list()
      .pipe(catchError(() => EMPTY))
      .subscribe((personas) => this.personas.set(personas));

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

    this.draftsSubscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.contentDraftsService.list().pipe(
            catchError(() => {
              this.draftsLoadState.set('error');
              return EMPTY;
            }),
          ),
        ),
      )
      .subscribe((drafts) => {
        this.drafts.set(drafts);
        this.draftsLoadState.set('ready');
      });

    this.schedulesSubscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.publishSchedulesService.list().pipe(
            catchError(() => {
              this.schedulesLoadState.set('error');
              return EMPTY;
            }),
          ),
        ),
      )
      .subscribe((schedules) => {
        this.schedules.set(schedules);
        this.schedulesLoadState.set('ready');
      });

    this.contentSourcesSubscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.contentSourcesService.list().pipe(
            catchError(() => {
              this.contentSourcesLoadState.set('error');
              return EMPTY;
            }),
          ),
        ),
      )
      .subscribe((sources) => {
        this.contentSources.set(sources);
        this.contentSourcesLoadState.set('ready');
      });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.draftsSubscription?.unsubscribe();
    this.schedulesSubscription?.unsubscribe();
    this.contentSourcesSubscription?.unsubscribe();
    this.aiPollers.forEach((poller) => poller.unsubscribe());
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
    if (analyzerType === 'DETERMINISTIC_V2') {
      return 'Semantic Highlights V2';
    }
    return analyzerType;
  }

  protected isV2Candidate(candidate: HighlightCandidateSummary): boolean {
    return candidate.hookScore !== null;
  }

  /** Friendly, deterministic wording for V2's explanation labels — no AI generation. */
  protected explanationText(label: string): string {
    switch (label) {
      case 'CLEAN_OPENING':
        return 'Starts at a clean sentence boundary.';
      case 'QUESTION_OPENING':
        return 'Opens with a question.';
      case 'COMPLETE_SENTENCE_BOUNDARIES':
        return 'Starts and ends at complete sentence boundaries.';
      case 'HIGH_SPEECH_DENSITY':
        return 'Well-paced, mostly continuous speech.';
      case 'HIGH_INFORMATION_DENSITY':
        return 'Dense with content, few filler words.';
      case 'LEADING_SILENCE_PENALTY':
        return 'Has a pause before speech starts.';
      case 'LOW_TRANSCRIPT_COVERAGE':
        return 'Limited transcript evidence for this window.';
      case 'REPETITIVE_CONTENT_PENALTY':
        return 'Echoes another highlight’s content.';
      default:
        return label;
    }
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

  /** SEMANTIC_HIGHLIGHTS_V2 is deterministic (no LLM), but still transcript-driven like TRANSCRIPT_SEMANTIC_V1. */
  protected canAnalyzeV2Highlights(asset: MediaAssetSummary): boolean {
    return this.canAnalyzeSemanticHighlights(asset);
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

  /** SEMANTIC_HIGHLIGHTS_V2 — deterministic multimodal candidate ranking, no LLM required. */
  protected findHighlightsV2(asset: MediaAssetSummary): void {
    this.startHighlightAnalysis(asset, 'DETERMINISTIC_V2', 'A completed transcript is required for Semantic Highlights V2.', 'Semantic Highlights V2 analysis could not be started.');
  }

  private startHighlightAnalysis(asset: MediaAssetSummary, analyzer: string, validationMessage: string, errorMessage: string): void {
    this.highlightErrors.update((errors) => ({ ...errors, [asset.id]: null }));
    const transcriptDriven = analyzer === 'TRANSCRIPT_SEMANTIC_V1' || analyzer === 'DETERMINISTIC_V2';
    if (transcriptDriven ? !this.canAnalyzeSemanticHighlights(asset) : !this.canAnalyzeHighlights(asset)) {
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

  protected publishableSocialAccounts(): SocialAccountSummary[] {
    return this.socialAccounts().filter((account) => account.status === 'ACTIVE');
  }

  protected instagramEligibilityHint(asset: MediaAssetSummary): string | null {
    if (asset.durationMs === null) {
      return null;
    }
    if (asset.durationMs < 3000) {
      return 'Instagram Reels require at least 3 seconds of video.';
    }
    if (asset.durationMs > 15 * 60 * 1000) {
      return 'Instagram Reels must be 15 minutes or shorter.';
    }
    return null;
  }

  protected publicationsFor(asset: MediaAssetSummary): PublicationSummary[] {
    return this.publications()[asset.id] ?? [];
  }

  protected publishAccountFor(asset: MediaAssetSummary): string {
    return this.publishAccountId()[asset.id] ?? this.publishableSocialAccounts()[0]?.id ?? '';
  }

  protected selectedAccountFor(asset: MediaAssetSummary): SocialAccountSummary | null {
    const accountId = this.publishAccountFor(asset);
    return this.publishableSocialAccounts().find((account) => account.id === accountId) ?? null;
  }

  protected setPublishAccount(asset: MediaAssetSummary, value: string): void {
    this.publishAccountId.update((ids) => ({ ...ids, [asset.id]: value }));
    const account = this.socialAccounts().find((candidate) => candidate.id === value);
    if (account?.platform === 'TIKTOK') {
      this.socialAccountsService.publishingCapabilities(value).subscribe({
        next: (capabilities) => this.tiktokCapabilities.update((items) => ({ ...items, [asset.id]: capabilities })),
        error: () => this.publishErrors.update((errors) => ({ ...errors, [asset.id]: 'TikTok creator settings could not be loaded.' })),
      });
    }
  }

  protected setTikTokPrivacy(asset: MediaAssetSummary, value: string): void { this.tiktokPrivacy.update((items) => ({ ...items, [asset.id]: value })); }
  protected setTikTokFlag(kind: 'comment'|'duet'|'stitch', asset: MediaAssetSummary, value: boolean): void {
    const target = kind === 'comment' ? this.tiktokDisableComment : kind === 'duet' ? this.tiktokDisableDuet : this.tiktokDisableStitch;
    target.update((items) => ({ ...items, [asset.id]: value }));
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
      this.publishErrors.update((errors) => ({ ...errors, [asset.id]: 'Connect a social account first.' }));
      return;
    }
    const caption = this.publishCaptionFor(asset).trim();
    const account = this.selectedAccountFor(asset);
    const capabilities = this.tiktokCapabilities()[asset.id];
    const privacyLevel = this.tiktokPrivacy()[asset.id] ?? '';
    if (account?.platform === 'TIKTOK' && (!capabilities || !privacyLevel)) {
      this.publishErrors.update((errors) => ({ ...errors, [asset.id]: 'Load TikTok creator settings and choose privacy before publishing.' }));
      return;
    }
    const tiktokSettings = account?.platform === 'TIKTOK' ? {
      privacyLevel, disableComment: this.tiktokDisableComment()[asset.id] ?? capabilities!.commentDisabled,
      disableDuet: this.tiktokDisableDuet()[asset.id] ?? capabilities!.duetDisabled,
      disableStitch: this.tiktokDisableStitch()[asset.id] ?? capabilities!.stitchDisabled,
    } : undefined;
    this.publishBusy.update((busy) => ({ ...busy, [asset.id]: true }));
    const request = tiktokSettings
      ? this.publishingService.createPublication(asset.id, socialAccountId, caption || null, tiktokSettings)
      : this.publishingService.createPublication(asset.id, socialAccountId, caption || null);
    request
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

  protected switchView(view: 'assets' | 'drafts' | 'schedule' | 'sources'): void {
    this.activeView.set(view);
  }

  // ---- content sources ----

  protected canAddToSource(asset: MediaAssetSummary): boolean {
    return asset.derivationType === 'ORIGINAL';
  }

  protected activeContentSources(): ContentSourceSummary[] {
    return this.contentSources().filter((source) => source.status === 'ACTIVE');
  }

  protected createContentSource(): void {
    this.sourceCreateError.set(null);
    const name = this.sourceCreateName().trim();
    if (!name) {
      this.sourceCreateError.set('Name is required.');
      return;
    }
    this.sourceCreateBusy.set(true);
    this.contentSourcesService
      .create({ name, description: this.sourceCreateDescription().trim() || null })
      .pipe(finalize(() => this.sourceCreateBusy.set(false)))
      .subscribe({
        next: (source) => {
          this.contentSources.set([source, ...this.contentSources()]);
          this.sourceCreateName.set('');
          this.sourceCreateDescription.set('');
        },
        error: () => this.sourceCreateError.set('Content source could not be created.'),
      });
  }

  protected toggleSourcePause(source: ContentSourceSummary): void {
    this.sourcePauseResumeBusy.update((busy) => ({ ...busy, [source.id]: true }));
    const action = source.status === 'ACTIVE' ? this.contentSourcesService.pause(source.id) : this.contentSourcesService.resume(source.id);
    action.pipe(finalize(() => this.sourcePauseResumeBusy.update((busy) => ({ ...busy, [source.id]: false })))).subscribe({
      next: (updated) => this.replaceContentSource(updated),
      error: () => undefined,
    });
  }

  private replaceContentSource(source: ContentSourceSummary): void {
    this.contentSources.set(this.contentSources().map((existing) => (existing.id === source.id ? source : existing)));
  }

  protected isSourceExpanded(source: ContentSourceSummary): boolean {
    return this.expandedSources()[source.id] ?? false;
  }

  protected toggleSourceExpanded(source: ContentSourceSummary): void {
    const expanded = !(this.expandedSources()[source.id] ?? false);
    this.expandedSources.update((items) => ({ ...items, [source.id]: expanded }));
    if (expanded) {
      this.loadSourceAssets(source);
    }
  }

  private loadSourceAssets(source: ContentSourceSummary): void {
    this.contentSourcesService
      .listAssets(source.id)
      .pipe(catchError(() => EMPTY))
      .subscribe((assets) => this.sourceAssets.update((items) => ({ ...items, [source.id]: assets })));
  }

  protected assetsInSource(source: ContentSourceSummary): ContentSourceAssetSummary[] {
    return this.sourceAssets()[source.id] ?? [];
  }

  protected sourceAssetReadinessLabel(asset: ContentSourceAssetSummary): string {
    switch (asset.status) {
      case 'PENDING':
        return 'Waiting';
      case 'IMPORTING':
      case 'PROCESSING':
        return 'Processing';
      case 'FAILED':
        return 'Failed';
      case 'READY':
        return asset.inspectionStatus === 'INSPECTED' ? 'Eligible' : 'Not yet eligible';
    }
  }

  protected candidateAssetsForSource(source: ContentSourceSummary): MediaAssetSummary[] {
    const memberIds = new Set(this.assetsInSource(source).map((member) => member.mediaAssetId));
    return this.assets().filter((asset) => asset.derivationType === 'ORIGINAL' && !memberIds.has(asset.id));
  }

  protected sourceAddAssetFor(source: ContentSourceSummary): string {
    return this.sourceAddAssetId()[source.id] ?? '';
  }

  protected setSourceAddAsset(source: ContentSourceSummary, value: string): void {
    this.sourceAddAssetId.update((ids) => ({ ...ids, [source.id]: value }));
  }

  protected addAssetToSource(source: ContentSourceSummary): void {
    const assetId = this.sourceAddAssetFor(source);
    this.sourceAddErrors.update((errors) => ({ ...errors, [source.id]: null }));
    if (!assetId) {
      this.sourceAddErrors.update((errors) => ({ ...errors, [source.id]: 'Choose an asset to add.' }));
      return;
    }
    this.sourceAddBusy.update((busy) => ({ ...busy, [source.id]: true }));
    this.contentSourcesService
      .addAsset(source.id, assetId)
      .pipe(finalize(() => this.sourceAddBusy.update((busy) => ({ ...busy, [source.id]: false }))))
      .subscribe({
        next: (added) => {
          this.sourceAssets.update((items) => ({ ...items, [source.id]: [...(items[source.id] ?? []), added] }));
          this.sourceAddAssetId.update((ids) => ({ ...ids, [source.id]: '' }));
          this.replaceContentSource({ ...source, assetCount: source.assetCount + 1 });
        },
        error: () => this.sourceAddErrors.update((errors) => ({ ...errors, [source.id]: 'Asset could not be added.' })),
      });
  }

  protected removeAssetFromSource(source: ContentSourceSummary, asset: ContentSourceAssetSummary): void {
    const key = `${source.id}:${asset.mediaAssetId}`;
    this.sourceRemoveBusy.update((busy) => ({ ...busy, [key]: true }));
    this.contentSourcesService
      .removeAsset(source.id, asset.mediaAssetId)
      .pipe(finalize(() => this.sourceRemoveBusy.update((busy) => ({ ...busy, [key]: false }))))
      .subscribe({
        next: () => {
          this.sourceAssets.update((items) => ({
            ...items,
            [source.id]: (items[source.id] ?? []).filter((member) => member.mediaAssetId !== asset.mediaAssetId),
          }));
          this.replaceContentSource({ ...source, assetCount: Math.max(0, source.assetCount - 1) });
        },
        error: () => undefined,
      });
  }

  protected isRemovingFromSource(source: ContentSourceSummary, asset: ContentSourceAssetSummary): boolean {
    return this.sourceRemoveBusy()[`${source.id}:${asset.mediaAssetId}`] ?? false;
  }

  // ---- add-to-source from the Assets tab ----

  protected addToSourceSelectionFor(asset: MediaAssetSummary): string {
    return this.addToSourceSelection()[asset.id] ?? this.activeContentSources()[0]?.id ?? '';
  }

  protected setAddToSourceSelection(asset: MediaAssetSummary, value: string): void {
    this.addToSourceSelection.update((ids) => ({ ...ids, [asset.id]: value }));
  }

  protected addAssetToSelectedSource(asset: MediaAssetSummary): void {
    const sourceId = this.addToSourceSelectionFor(asset);
    this.addToSourceErrors.update((errors) => ({ ...errors, [asset.id]: null }));
    this.addToSourceDone.update((done) => ({ ...done, [asset.id]: false }));
    if (!sourceId) {
      this.addToSourceErrors.update((errors) => ({ ...errors, [asset.id]: 'Create a content source first.' }));
      return;
    }
    this.addToSourceBusy.update((busy) => ({ ...busy, [asset.id]: true }));
    this.contentSourcesService
      .addAsset(sourceId, asset.id)
      .pipe(finalize(() => this.addToSourceBusy.update((busy) => ({ ...busy, [asset.id]: false }))))
      .subscribe({
        next: () => {
          this.addToSourceDone.update((done) => ({ ...done, [asset.id]: true }));
          const source = this.contentSources().find((existing) => existing.id === sourceId);
          if (source) {
            this.replaceContentSource({ ...source, assetCount: source.assetCount + 1 });
          }
        },
        // The import/asset itself already succeeded; only the source
        // association failed, and that failure must be surfaced accurately
        // rather than silently swallowed or mistaken for an import failure.
        error: () => this.addToSourceErrors.update((errors) => ({ ...errors, [asset.id]: 'Could not add to content source.' })),
      });
  }

  protected canCreateDraft(asset: MediaAssetSummary): boolean {
    return asset.status === 'READY' && asset.inspectionStatus === 'INSPECTED' && asset.hasVideo === true;
  }

  protected createDraftFromAsset(asset: MediaAssetSummary): void {
    this.draftCreateErrors.update((errors) => ({ ...errors, [asset.id]: null }));
    this.draftCreateBusy.update((busy) => ({ ...busy, [asset.id]: true }));
    this.contentDraftsService
      .createFromAsset(asset.id, null, null)
      .pipe(finalize(() => this.draftCreateBusy.update((busy) => ({ ...busy, [asset.id]: false }))))
      .subscribe({
        next: (draft) => {
          this.drafts.set([draft, ...this.drafts().filter((existing) => existing.id !== draft.id)]);
          this.draftsLoadState.set('ready');
          this.activeView.set('drafts');
        },
        error: () => this.draftCreateErrors.update((errors) => ({ ...errors, [asset.id]: 'Draft could not be created.' })),
      });
  }

  protected createDraftFromCandidate(candidate: HighlightCandidateSummary): void {
    this.draftCreateErrors.update((errors) => ({ ...errors, [candidate.id]: null }));
    this.draftCreateBusy.update((busy) => ({ ...busy, [candidate.id]: true }));
    this.contentDraftsService
      .createFromHighlightCandidate(candidate.id)
      .pipe(finalize(() => this.draftCreateBusy.update((busy) => ({ ...busy, [candidate.id]: false }))))
      .subscribe({
        next: (draft) => {
          this.drafts.set([draft, ...this.drafts().filter((existing) => existing.id !== draft.id)]);
          this.draftsLoadState.set('ready');
          this.activeView.set('drafts');
        },
        error: () => this.draftCreateErrors.update((errors) => ({ ...errors, [candidate.id]: 'Draft could not be created.' })),
      });
  }

  protected isDraftExpanded(draft: ContentDraftSummary): boolean {
    return this.expandedDrafts()[draft.id] ?? false;
  }

  protected toggleDraft(draft: ContentDraftSummary): void {
    const expanded = !(this.expandedDrafts()[draft.id] ?? false);
    this.expandedDrafts.update((items) => ({ ...items, [draft.id]: expanded }));
    if (expanded && !this.aiSuggestions()[draft.id]) {
      this.loadSuggestions(draft.id);
    }
  }

  protected draftTitle(draft: ContentDraftSummary): string {
    return draft.title || draft.mediaAssetFilename || 'Untitled draft';
  }

  protected draftSourceTitle(draft: ContentDraftSummary): string {
    const source = this.assets().find((item) => item.id === draft.sourceAssetId);
    return source ? this.assetTitle(source) : draft.sourceAssetId.slice(0, 8);
  }

  protected draftMediaTitle(draft: ContentDraftSummary): string {
    return draft.mediaAssetFilename || draft.mediaAssetId.slice(0, 8);
  }

  protected draftStatusLabel(status: ContentDraftStatus): string {
    switch (status) {
      case 'DRAFT':
        return 'Preparing';
      case 'READY':
        return 'Ready';
      case 'PUBLISHING':
        return 'Publishing';
      case 'PUBLISHED':
        return 'Published';
      case 'FAILED':
        return 'Failed';
    }
  }

  protected draftStageLabel(stage: ContentDraftWorkflowStage): string {
    switch (stage) {
      case 'CLIP_PENDING':
        return 'Creating clip';
      case 'VERTICAL_PENDING':
        return 'Preparing vertical';
      case 'READY':
        return 'Ready';
    }
  }

  protected draftTitleFor(draft: ContentDraftSummary): string {
    return this.draftTitleEdits()[draft.id] ?? draft.title ?? '';
  }

  protected setDraftTitle(draft: ContentDraftSummary, value: string): void {
    this.draftTitleEdits.update((edits) => ({ ...edits, [draft.id]: value }));
  }

  protected draftCaptionFor(draft: ContentDraftSummary): string {
    return this.draftCaptionEdits()[draft.id] ?? draft.caption ?? '';
  }

  protected setDraftCaption(draft: ContentDraftSummary, value: string): void {
    this.draftCaptionEdits.update((edits) => ({ ...edits, [draft.id]: value }));
  }

  protected saveDraftEdits(draft: ContentDraftSummary): void {
    this.draftSaveErrors.update((errors) => ({ ...errors, [draft.id]: null }));
    this.draftSaveBusy.update((busy) => ({ ...busy, [draft.id]: true }));
    const title = this.draftTitleFor(draft).trim() || null;
    const caption = this.draftCaptionFor(draft).trim() || null;
    this.contentDraftsService
      .update(draft.id, title, caption)
      .pipe(finalize(() => this.draftSaveBusy.update((busy) => ({ ...busy, [draft.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceDraft(updated),
        error: () => this.draftSaveErrors.update((errors) => ({ ...errors, [draft.id]: 'Draft could not be saved.' })),
      });
  }

  protected draftPublishAccountFor(draft: ContentDraftSummary): string {
    return this.draftPublishAccountId()[draft.id] ?? this.publishableSocialAccounts()[0]?.id ?? '';
  }

  protected setDraftPublishAccount(draft: ContentDraftSummary, value: string): void {
    this.draftPublishAccountId.update((ids) => ({ ...ids, [draft.id]: value }));
  }

  protected canPublishDraft(draft: ContentDraftSummary): boolean {
    return draft.status === 'READY' || draft.status === 'PUBLISHED';
  }

  protected publishDraft(draft: ContentDraftSummary): void {
    this.draftPublishErrors.update((errors) => ({ ...errors, [draft.id]: null }));
    const socialAccountId = this.draftPublishAccountFor(draft);
    if (!this.canPublishDraft(draft)) {
      this.draftPublishErrors.update((errors) => ({ ...errors, [draft.id]: 'Draft is not ready to publish.' }));
      return;
    }
    if (!socialAccountId) {
      this.draftPublishErrors.update((errors) => ({ ...errors, [draft.id]: 'Connect a social account first.' }));
      return;
    }
    this.draftPublishBusy.update((busy) => ({ ...busy, [draft.id]: true }));
    this.contentDraftsService
      .publish(draft.id, socialAccountId)
      .pipe(finalize(() => this.draftPublishBusy.update((busy) => ({ ...busy, [draft.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceDraft(updated),
        error: () => this.draftPublishErrors.update((errors) => ({ ...errors, [draft.id]: 'Publication could not be started.' })),
      });
  }

  protected canRetryDraft(draft: ContentDraftSummary): boolean {
    return draft.status === 'FAILED';
  }

  protected retryDraftPreparation(draft: ContentDraftSummary): void {
    this.draftRetryErrors.update((errors) => ({ ...errors, [draft.id]: null }));
    this.draftRetryBusy.update((busy) => ({ ...busy, [draft.id]: true }));
    this.contentDraftsService
      .retryPreparation(draft.id)
      .pipe(finalize(() => this.draftRetryBusy.update((busy) => ({ ...busy, [draft.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceDraft(updated),
        error: () => this.draftRetryErrors.update((errors) => ({ ...errors, [draft.id]: 'Retry could not be started.' })),
      });
  }

  private replaceDraft(draft: ContentDraftSummary): void {
    this.drafts.set(this.drafts().map((existing) => (existing.id === draft.id ? draft : existing)));
  }

  protected canSchedule(draft: ContentDraftSummary): boolean {
    return draft.status === 'READY' || draft.status === 'PUBLISHED';
  }

  protected draftScheduleAccountFor(draft: ContentDraftSummary): string {
    return this.draftScheduleAccountId()[draft.id] ?? this.publishableSocialAccounts()[0]?.id ?? '';
  }

  protected setDraftScheduleAccount(draft: ContentDraftSummary, value: string): void {
    this.draftScheduleAccountId.update((ids) => ({ ...ids, [draft.id]: value }));
  }

  protected draftScheduleDateTimeFor(draft: ContentDraftSummary): string {
    return this.draftScheduleDateTime()[draft.id] ?? '';
  }

  protected setDraftScheduleDateTime(draft: ContentDraftSummary, value: string): void {
    this.draftScheduleDateTime.update((values) => ({ ...values, [draft.id]: value }));
  }

  protected scheduleDraft(draft: ContentDraftSummary): void {
    this.scheduleCreateErrors.update((errors) => ({ ...errors, [draft.id]: null }));
    const socialAccountId = this.draftScheduleAccountFor(draft);
    const localDateTime = this.draftScheduleDateTimeFor(draft);
    if (!socialAccountId) {
      this.scheduleCreateErrors.update((errors) => ({ ...errors, [draft.id]: 'Connect a social account first.' }));
      return;
    }
    const scheduledFor = this.toIsoInstant(localDateTime);
    if (!scheduledFor) {
      this.scheduleCreateErrors.update((errors) => ({ ...errors, [draft.id]: 'Choose a valid future date and time.' }));
      return;
    }
    this.scheduleCreateBusy.update((busy) => ({ ...busy, [draft.id]: true }));
    this.publishSchedulesService
      .create(draft.id, socialAccountId, scheduledFor)
      .pipe(finalize(() => this.scheduleCreateBusy.update((busy) => ({ ...busy, [draft.id]: false }))))
      .subscribe({
        next: (schedule) => {
          this.schedules.set([schedule, ...this.schedules()]);
          this.draftScheduleDateTime.update((values) => ({ ...values, [draft.id]: '' }));
        },
        error: () => this.scheduleCreateErrors.update((errors) => ({ ...errors, [draft.id]: 'Schedule could not be created.' })),
      });
  }

  /** datetime-local values have no timezone; the browser's Date parses them as
   *  local time, and toISOString() converts that to an unambiguous UTC instant. */
  private toIsoInstant(localDateTime: string): string | null {
    if (!localDateTime) {
      return null;
    }
    const parsed = new Date(localDateTime);
    if (Number.isNaN(parsed.getTime())) {
      return null;
    }
    return parsed.toISOString();
  }

  protected upcomingSchedules(): PublishScheduleSummary[] {
    return this.schedules()
      .filter((schedule) => schedule.status === 'SCHEDULED')
      .sort((a, b) => a.scheduledFor.localeCompare(b.scheduledFor));
  }

  protected scheduleHistory(): PublishScheduleSummary[] {
    return this.schedules()
      .filter((schedule) => schedule.status !== 'SCHEDULED')
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }

  protected schedulesForDraft(draft: ContentDraftSummary): PublishScheduleSummary[] {
    return this.schedules()
      .filter((schedule) => schedule.contentDraftId === draft.id)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  protected scheduleStatusLabel(status: PublishScheduleStatus): string {
    switch (status) {
      case 'SCHEDULED':
        return 'Scheduled';
      case 'DISPATCHED':
        return 'Dispatched';
      case 'CANCELLED':
        return 'Cancelled';
      case 'FAILED':
        return 'Failed';
    }
  }

  protected canCancelSchedule(schedule: PublishScheduleSummary): boolean {
    return schedule.status === 'SCHEDULED';
  }

  protected cancelSchedule(schedule: PublishScheduleSummary): void {
    this.scheduleCancelErrors.update((errors) => ({ ...errors, [schedule.id]: null }));
    this.scheduleCancelBusy.update((busy) => ({ ...busy, [schedule.id]: true }));
    this.publishSchedulesService
      .cancel(schedule.id)
      .pipe(finalize(() => this.scheduleCancelBusy.update((busy) => ({ ...busy, [schedule.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceSchedule(updated),
        error: () => this.scheduleCancelErrors.update((errors) => ({ ...errors, [schedule.id]: 'Could not cancel schedule.' })),
      });
  }

  protected isRescheduling(schedule: PublishScheduleSummary): boolean {
    return this.rescheduleEditing()[schedule.id] ?? false;
  }

  protected startReschedule(schedule: PublishScheduleSummary): void {
    this.rescheduleEditing.update((items) => ({ ...items, [schedule.id]: true }));
  }

  protected cancelRescheduleEdit(schedule: PublishScheduleSummary): void {
    this.rescheduleEditing.update((items) => ({ ...items, [schedule.id]: false }));
  }

  protected rescheduleDateTimeFor(schedule: PublishScheduleSummary): string {
    return this.rescheduleDateTime()[schedule.id] ?? '';
  }

  protected setRescheduleDateTime(schedule: PublishScheduleSummary, value: string): void {
    this.rescheduleDateTime.update((values) => ({ ...values, [schedule.id]: value }));
  }

  protected submitReschedule(schedule: PublishScheduleSummary): void {
    this.rescheduleErrors.update((errors) => ({ ...errors, [schedule.id]: null }));
    const scheduledFor = this.toIsoInstant(this.rescheduleDateTimeFor(schedule));
    if (!scheduledFor) {
      this.rescheduleErrors.update((errors) => ({ ...errors, [schedule.id]: 'Choose a valid future date and time.' }));
      return;
    }
    this.rescheduleBusy.update((busy) => ({ ...busy, [schedule.id]: true }));
    this.publishSchedulesService
      .reschedule(schedule.id, scheduledFor)
      .pipe(finalize(() => this.rescheduleBusy.update((busy) => ({ ...busy, [schedule.id]: false }))))
      .subscribe({
        next: (updated) => {
          this.replaceSchedule(updated);
          this.rescheduleEditing.update((items) => ({ ...items, [schedule.id]: false }));
        },
        error: () => this.rescheduleErrors.update((errors) => ({ ...errors, [schedule.id]: 'Could not reschedule.' })),
      });
  }

  private replaceSchedule(schedule: PublishScheduleSummary): void {
    this.schedules.set(this.schedules().map((existing) => (existing.id === schedule.id ? schedule : existing)));
  }

  // ---- AI content enrichment ----

  protected canGenerateSuggestion(draft: ContentDraftSummary): boolean {
    return draft.status === 'READY';
  }

  protected suggestionsFor(draft: ContentDraftSummary): ContentSuggestionSummary[] {
    return this.aiSuggestions()[draft.id] ?? [];
  }

  private loadSuggestions(draftId: string): void {
    this.contentSuggestionsService
      .listForDraft(draftId)
      .pipe(catchError(() => EMPTY))
      .subscribe((list) => {
        this.aiSuggestions.update((items) => ({ ...items, [draftId]: list }));
        list.filter((s) => s.status === 'PENDING' || s.status === 'GENERATING').forEach((s) => this.pollSuggestion(draftId, s.id));
      });
  }

  protected aiLanguageFor(draft: ContentDraftSummary): SuggestionLanguage {
    return this.aiLanguage()[draft.id] ?? 'AUTO';
  }

  protected setAiLanguage(draft: ContentDraftSummary, value: SuggestionLanguage): void {
    this.aiLanguage.update((items) => ({ ...items, [draft.id]: value }));
  }

  protected aiToneFor(draft: ContentDraftSummary): SuggestionTone {
    return this.aiTone()[draft.id] ?? 'NEUTRAL';
  }

  protected setAiTone(draft: ContentDraftSummary, value: SuggestionTone): void {
    this.aiTone.update((items) => ({ ...items, [draft.id]: value }));
  }

  protected activePersonas(): PersonaSummary[] {
    return this.personas().filter((persona) => persona.status === 'ACTIVE');
  }

  protected aiPersonaIdFor(draft: ContentDraftSummary): string {
    return this.aiPersonaId()[draft.id] ?? '';
  }

  /** Selecting a Persona pre-fills language/tone from its defaults — a one-time convenience the human may still override before Generate. */
  protected setAiPersonaId(draft: ContentDraftSummary, value: string): void {
    this.aiPersonaId.update((items) => ({ ...items, [draft.id]: value }));
    const persona = this.personas().find((candidate) => candidate.id === value);
    if (persona) {
      this.aiLanguage.update((items) => ({ ...items, [draft.id]: persona.defaultLanguage }));
      this.aiTone.update((items) => ({ ...items, [draft.id]: persona.defaultTone }));
    }
  }

  protected generateSuggestion(draft: ContentDraftSummary): void {
    this.aiGenerateErrors.update((errors) => ({ ...errors, [draft.id]: null }));
    this.aiGenerateBusy.update((busy) => ({ ...busy, [draft.id]: true }));
    const personaId = this.aiPersonaIdFor(draft) || null;
    this.contentSuggestionsService
      .create(draft.id, { language: this.aiLanguageFor(draft), tone: this.aiToneFor(draft), personaId })
      .pipe(finalize(() => this.aiGenerateBusy.update((busy) => ({ ...busy, [draft.id]: false }))))
      .subscribe({
        next: (suggestion) => {
          this.aiSuggestions.update((items) => ({ ...items, [draft.id]: [suggestion, ...(items[draft.id] ?? [])] }));
          if (suggestion.status === 'PENDING' || suggestion.status === 'GENERATING') {
            this.pollSuggestion(draft.id, suggestion.id);
          }
        },
        error: () => this.aiGenerateErrors.update((errors) => ({ ...errors, [draft.id]: 'Suggestion could not be generated.' })),
      });
  }

  /** Durable-state polling, not a held-open request: the LLM call runs in a distributed Job, so this just re-reads suggestion state until it leaves PENDING/GENERATING. */
  private pollSuggestion(draftId: string, suggestionId: string): void {
    if (this.aiPollers.has(suggestionId)) {
      return;
    }
    const poller = interval(3000)
      .pipe(
        startWith(0),
        switchMap(() => this.contentSuggestionsService.listForDraft(draftId).pipe(catchError(() => EMPTY))),
      )
      .subscribe((list) => {
        this.aiSuggestions.update((items) => ({ ...items, [draftId]: list }));
        const current = list.find((s) => s.id === suggestionId);
        if (!current || (current.status !== 'PENDING' && current.status !== 'GENERATING')) {
          this.aiPollers.get(suggestionId)?.unsubscribe();
          this.aiPollers.delete(suggestionId);
        }
      });
    this.aiPollers.set(suggestionId, poller);
  }

  protected applySuggestion(draft: ContentDraftSummary, suggestion: ContentSuggestionSummary): void {
    this.aiApplyErrors.update((errors) => ({ ...errors, [suggestion.id]: null }));
    this.aiApplyBusy.update((busy) => ({ ...busy, [suggestion.id]: true }));
    this.contentSuggestionsService
      .apply(suggestion.id)
      .pipe(finalize(() => this.aiApplyBusy.update((busy) => ({ ...busy, [suggestion.id]: false }))))
      .subscribe({
        next: (updated) => {
          this.replaceSuggestion(draft.id, updated);
          this.refreshDraft(draft.id);
        },
        error: () => this.aiApplyErrors.update((errors) => ({
          ...errors,
          [suggestion.id]: suggestion.stale
            ? 'Draft changed since this suggestion was generated. Regenerate to apply fresh copy.'
            : 'Suggestion could not be applied.',
        })),
      });
  }

  protected discardSuggestion(draft: ContentDraftSummary, suggestion: ContentSuggestionSummary): void {
    this.aiDiscardBusy.update((busy) => ({ ...busy, [suggestion.id]: true }));
    this.contentSuggestionsService
      .discard(suggestion.id)
      .pipe(finalize(() => this.aiDiscardBusy.update((busy) => ({ ...busy, [suggestion.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceSuggestion(draft.id, updated),
        error: () => undefined,
      });
  }

  private replaceSuggestion(draftId: string, suggestion: ContentSuggestionSummary): void {
    this.aiSuggestions.update((items) => ({
      ...items,
      [draftId]: (items[draftId] ?? []).map((existing) => (existing.id === suggestion.id ? suggestion : existing)),
    }));
  }

  private refreshDraft(draftId: string): void {
    this.contentDraftsService
      .list()
      .pipe(catchError(() => EMPTY))
      .subscribe((list) => this.drafts.set(list));
  }

  protected suggestionStatusLabel(status: ContentSuggestionSummary['status']): string {
    switch (status) {
      case 'PENDING':
        return 'Queued';
      case 'GENERATING':
        return 'Generating';
      case 'READY':
        return 'Ready for review';
      case 'FAILED':
        return 'Failed';
      case 'APPLIED':
        return 'Applied';
      case 'DISCARDED':
        return 'Discarded';
    }
  }

  protected canApplySuggestion(suggestion: ContentSuggestionSummary): boolean {
    return suggestion.status === 'READY' && !suggestion.stale;
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
