import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { HighlightAnalysisSummary, MediaAssetSummary, MediaAssetStatus, MediaTranscriptSummary } from '../../core/assets/asset.models';
import { PublishingService } from '../../core/publishing/publishing.service';
import { PublicationSummary } from '../../core/publishing/publishing.models';
import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import { SocialAccountSummary } from '../../core/social-accounts/social-account.models';
import { ContentDraftsService } from '../../core/content-drafts/content-drafts.service';
import { ContentDraftSummary } from '../../core/content-drafts/content-draft.models';
import { PublishSchedulesService } from '../../core/publish-schedules/publish-schedules.service';
import { PublishScheduleSummary } from '../../core/publish-schedules/publish-schedule.models';
import { Content } from './content';

describe('Content', () => {
  let component: Content;
  let fixture: ComponentFixture<Content>;
  let assetsService: Pick<
    AssetsService,
    | 'list'
    | 'importUrl'
    | 'createClip'
    | 'createSocialVertical'
    | 'createHighlightAnalysis'
    | 'listHighlightAnalyses'
    | 'createClipFromHighlightCandidate'
    | 'createTranscript'
    | 'listTranscripts'
  >;
  let publishingService: Pick<PublishingService, 'createPublication' | 'listForAsset'>;
  let socialAccountsService: Pick<SocialAccountsService, 'list' | 'create'>;
  let contentDraftsService: Pick<
    ContentDraftsService,
    'list' | 'createFromAsset' | 'createFromHighlightCandidate' | 'update' | 'publish' | 'retryPreparation'
  >;
  let publishSchedulesService: Pick<PublishSchedulesService, 'list' | 'create' | 'cancel' | 'reschedule'>;

  beforeEach(async () => {
    assetsService = {
      list: vi.fn().mockReturnValue(of([
        asset('PENDING'),
        asset('IMPORTING'),
        asset('PROCESSING'),
        asset('READY'),
        asset('FAILED'),
      ])),
      importUrl: vi.fn().mockReturnValue(of({ asset: asset('PENDING') })),
      createClip: vi.fn().mockReturnValue(of({ asset: clipAsset() })),
      createSocialVertical: vi.fn().mockReturnValue(of({ asset: verticalAsset() })),
      createHighlightAnalysis: vi.fn().mockReturnValue(of(analysis('PENDING'))),
      listHighlightAnalyses: vi.fn().mockReturnValue(of([analysis('SUCCEEDED')])),
      createClipFromHighlightCandidate: vi.fn().mockReturnValue(of({ asset: clipAsset() })),
      createTranscript: vi.fn().mockReturnValue(of(transcript('PENDING'))),
      listTranscripts: vi.fn().mockReturnValue(of([transcript('SUCCEEDED')])),
    };
    socialAccountsService = {
      list: vi.fn().mockReturnValue(of([testAccount()])),
      create: vi.fn().mockReturnValue(of(testAccount())),
    };
    publishingService = {
      createPublication: vi.fn().mockReturnValue(of(publication('PENDING'))),
      listForAsset: vi.fn().mockReturnValue(of([])),
    };
    contentDraftsService = {
      list: vi.fn().mockReturnValue(of([])),
      createFromAsset: vi.fn().mockReturnValue(of(draft('DRAFT', 'CLIP_PENDING'))),
      createFromHighlightCandidate: vi.fn().mockReturnValue(of(draft('DRAFT', 'CLIP_PENDING'))),
      update: vi.fn().mockReturnValue(of(draft('READY', 'READY'))),
      publish: vi.fn().mockReturnValue(of(draft('PUBLISHING', 'READY'))),
      retryPreparation: vi.fn().mockReturnValue(of(draft('DRAFT', 'CLIP_PENDING'))),
    };
    publishSchedulesService = {
      list: vi.fn().mockReturnValue(of([])),
      create: vi.fn().mockReturnValue(of(schedule('SCHEDULED'))),
      cancel: vi.fn().mockReturnValue(of(schedule('CANCELLED'))),
      reschedule: vi.fn().mockReturnValue(of(schedule('SCHEDULED'))),
    };

    await TestBed.configureTestingModule({
      imports: [Content],
      providers: [
        { provide: AssetsService, useValue: assetsService },
        { provide: PublishingService, useValue: publishingService },
        { provide: SocialAccountsService, useValue: socialAccountsService },
        { provide: ContentDraftsService, useValue: contentDraftsService },
        { provide: PublishSchedulesService, useValue: publishSchedulesService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders media asset states and metadata', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;
    component['toggleAsset'](ready);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Waiting');
    expect(text).toContain('Processing');
    expect(text).toContain('Ready');
    expect(text).toContain('Failed');
    expect(text).toContain('video.mp4');
    expect(text).toContain('video/mp4');
    expect(text).toContain('1920x1080');
    expect(text).toContain('h264 / aac');
    expect(text).toContain('Inspected');
    expect(text).toContain('Create Clip');
    expect(text).toContain('Make 9:16');
    expect(text).toContain('Find Highlights');
    expect(text).toContain('Transcribe');
    expect(text).toContain('Local Whisper');
    expect(text).toContain('Hello world');
    expect(text).toContain('Baseline');
    expect(text).toContain('Deterministic Phase 7A candidate');
    expect(text).toContain('UNSUPPORTED_MEDIA');
  });

  it('renders empty state', async () => {
    vi.mocked(assetsService.list).mockReturnValue(of([]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    await fixture.whenStable();

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No media yet. Import a direct media URL to begin.');
  });

  it('renders API error state', async () => {
    vi.mocked(assetsService.list).mockReturnValue(throwError(() => new Error('Network failure')));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    await fixture.whenStable();

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Assets could not be loaded.');
  });

  it('keeps polling after a transient API error', async () => {
    vi.useFakeTimers();
    fixture.destroy();
    vi.mocked(assetsService.list)
      .mockReset()
      .mockReturnValueOnce(throwError(() => new Error('Network failure')))
      .mockReturnValueOnce(of([asset('READY')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;

    try {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Assets could not be loaded.');

      await vi.advanceTimersByTimeAsync(5000);
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('Ready');
      expect(assetsService.list).toHaveBeenCalledTimes(2);
    } finally {
      fixture.destroy();
      vi.useRealTimers();
    }
  });

  it('validates direct media URL before creating import', () => {
    fixture.detectChanges();

    component['url'].set('file:///tmp/video.mp4');
    component['importMedia']();
    fixture.detectChanges();

    expect(assetsService.importUrl).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Enter a direct http or https media URL.');
  });

  it('creates direct URL import and prepends returned asset', () => {
    fixture.detectChanges();

    component['url'].set('https://example.com/video.mp4');
    component['importMedia']();

    expect(assetsService.importUrl).toHaveBeenCalledWith('https://example.com/video.mp4');
    expect(component['assets']()[0].status).toBe('PENDING');
    expect(component['url']()).toBe('');
  });

  it('creates a clip for an inspected ready asset', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    component['setClipStart'](ready, '1000');
    component['setClipDuration'](ready, '2000');
    component['createClip'](ready);

    expect(assetsService.createClip).toHaveBeenCalledWith(ready.id, 1000, 2000);
    expect(component['assets']()[0].derivationType).toBe('CLIP');
  });

  it('creates a social vertical derivative for an inspected ready video asset', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    component['createSocialVertical'](ready);

    expect(assetsService.createSocialVertical).toHaveBeenCalledWith(ready.id);
    expect(component['assets']()[0].derivationType).toBe('SOCIAL_VERTICAL');
    expect(component['lineage'](component['assets']()[0])).toBe('Vertical from video.mp4');
  });

  it('renders asset details only after expansion', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    expect(fixture.nativeElement.textContent).not.toContain('Checksum');

    component['toggleAsset'](ready);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Overview');
    expect(fixture.nativeElement.textContent).toContain('Transcript');
    expect(fixture.nativeElement.textContent).toContain('Highlights');
    expect(fixture.nativeElement.textContent).toContain('Checksum');
  });

  it('humanizes semantic highlight labels and prerequisite state', () => {
    vi.mocked(assetsService.listTranscripts).mockReturnValue(of([]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    expect(component['canAnalyzeSemanticHighlights'](ready)).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Transcript AI needs transcript');

    component['transcripts'].set({ [ready.id]: transcript('SUCCEEDED') });
    vi.mocked(assetsService.createHighlightAnalysis).mockReturnValue(of({
      ...analysis('PENDING'),
      analyzerType: 'TRANSCRIPT_SEMANTIC_V1',
    }));

    component['findSemanticHighlights'](ready);

    expect(assetsService.createHighlightAnalysis).toHaveBeenCalledWith(ready.id, 'TRANSCRIPT_SEMANTIC_V1');
  });

  it('starts highlight analysis for an eligible asset', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    component['findHighlights'](ready);

    expect(assetsService.createHighlightAnalysis).toHaveBeenCalledWith(ready.id, 'DETERMINISTIC_V1');
    expect(component['highlightAnalysis'](ready)?.status).toBe('PENDING');
  });

  it('starts semantic highlight analysis only when a transcript is complete', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    expect(component['canAnalyzeSemanticHighlights'](ready)).toBe(true);
    component['findSemanticHighlights'](ready);

    expect(assetsService.createHighlightAnalysis).toHaveBeenCalledWith(ready.id, 'TRANSCRIPT_SEMANTIC_V1');
  });

  it('starts transcription for an inspected ready asset with audio', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    component['transcribe'](ready);

    expect(assetsService.createTranscript).toHaveBeenCalledWith(ready.id);
    expect(component['transcript'](ready)?.status).toBe('PENDING');
  });

  it('shows a Publish action only for inspected video assets and labels TEST as non-real', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;
    const pending = component['assets']().find((item) => item.status === 'PENDING')!;

    expect(component['canPublish'](ready)).toBe(true);
    expect(component['canPublish'](pending)).toBe(false);

    component['toggleAsset'](ready);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('not real');
  });

  it('publishes to the selected TEST social account and shows the returned publication', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    component['toggleAsset'](ready);
    component['setPublishAccount'](ready, 'account-1');
    component['setPublishCaption'](ready, 'Hello from the TEST provider');
    component['publish'](ready);

    expect(publishingService.createPublication).toHaveBeenCalledWith(ready.id, 'account-1', 'Hello from the TEST provider');
    expect(component['publicationsFor'](ready)[0].status).toBe('PENDING');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Queued');
  });

  it('requires a social account before publishing', () => {
    vi.mocked(socialAccountsService.list).mockReturnValue(of([]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    component['publish'](ready);

    expect(publishingService.createPublication).not.toHaveBeenCalled();
    expect(component['publishErrors']()[ready.id]).toBe('Connect a social account first.');
  });

  it('surfaces publication failures reported by the backend', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;
    vi.mocked(publishingService.listForAsset).mockReturnValue(of([publication('FAILED')]));

    component['refreshPublications'](component['assets']());
    fixture.detectChanges();

    expect(component['publicationsFor'](ready)[0].status).toBe('FAILED');
  });

  it('creates a clip from a highlight candidate', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;
    const candidate = analysis('SUCCEEDED').candidates[0];

    component['createClipFromCandidate'](candidate);

    expect(assetsService.createClipFromHighlightCandidate).toHaveBeenCalledWith(candidate.id);
    expect(component['assets']()[0].derivationType).toBe('CLIP');
    expect(component['highlightAnalysis'](ready)?.candidates[0].rank).toBe(1);
  });

  it('shows the empty drafts state', () => {
    fixture.detectChanges();
    component['switchView']('drafts');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No drafts yet.');
  });

  it('creates a draft from an eligible existing asset and switches to the drafts view', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;

    component['createDraftFromAsset'](ready);

    expect(contentDraftsService.createFromAsset).toHaveBeenCalledWith(ready.id, null, null);
    expect(component['activeView']()).toBe('drafts');
    expect(component['drafts']()[0].id).toBe('draft-1');
  });

  it('creates a draft from a highlight candidate', () => {
    fixture.detectChanges();
    const candidate = analysis('SUCCEEDED').candidates[0];

    component['createDraftFromCandidate'](candidate);

    expect(contentDraftsService.createFromHighlightCandidate).toHaveBeenCalledWith(candidate.id);
    expect(component['drafts']()[0].id).toBe('draft-1');
  });

  it('shows draft processing progress while a clip is pending', () => {
    vi.mocked(contentDraftsService.list).mockReturnValue(of([draft('DRAFT', 'CLIP_PENDING')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('drafts');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Preparing');
    expect(fixture.nativeElement.textContent).toContain('Creating clip');
  });

  it('lets a READY draft be edited and published to a TEST account', () => {
    vi.mocked(contentDraftsService.list).mockReturnValue(of([draft('READY', 'READY')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('drafts');
    const readyDraft = component['drafts']()[0];

    component['toggleDraft'](readyDraft);
    component['setDraftTitle'](readyDraft, 'My draft');
    component['setDraftCaption'](readyDraft, 'Caption text');
    component['saveDraftEdits'](readyDraft);

    expect(contentDraftsService.update).toHaveBeenCalledWith(readyDraft.id, 'My draft', 'Caption text');

    component['setDraftPublishAccount'](readyDraft, 'account-1');
    component['publishDraft'](readyDraft);

    expect(contentDraftsService.publish).toHaveBeenCalledWith(readyDraft.id, 'account-1');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Publishing');
  });

  it('labels TEST publications on a draft as non-real', () => {
    vi.mocked(contentDraftsService.list).mockReturnValue(of([draft('PUBLISHING', 'READY')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('drafts');
    const publishingDraft = component['drafts']()[0];
    component['toggleDraft'](publishingDraft);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('non-real');
  });

  it('surfaces a failed draft preparation and allows retry without destroying the draft', () => {
    vi.mocked(contentDraftsService.list).mockReturnValue(of([draft('FAILED', 'CLIP_PENDING')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('drafts');
    fixture.detectChanges();
    const failedDraft = component['drafts']()[0];

    expect(fixture.nativeElement.textContent).toContain('Encoding failed');
    expect(component['canRetryDraft'](failedDraft)).toBe(true);

    component['retryDraftPreparation'](failedDraft);

    expect(contentDraftsService.retryPreparation).toHaveBeenCalledWith(failedDraft.id);
  });

  it('shows an error state when drafts fail to load and renders without null/undefined leaking through', () => {
    vi.mocked(contentDraftsService.list).mockReturnValue(throwError(() => new Error('Network failure')));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('drafts');
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Drafts could not be loaded.');
    expect(text).not.toContain('undefined');
    expect(text).not.toContain('NaN');
  });

  it('shows the empty schedule state', () => {
    fixture.detectChanges();
    component['switchView']('schedule');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Nothing scheduled yet.');
  });

  it('creates a schedule from a READY draft using a local-time input converted to an ISO instant', () => {
    vi.mocked(contentDraftsService.list).mockReturnValue(of([draft('READY', 'READY')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const readyDraft = component['drafts']()[0];

    component['setDraftScheduleAccount'](readyDraft, 'account-1');
    component['setDraftScheduleDateTime'](readyDraft, '2026-09-20T18:30');
    component['scheduleDraft'](readyDraft);

    expect(publishSchedulesService.create).toHaveBeenCalledTimes(1);
    const [draftId, accountId, scheduledFor] = vi.mocked(publishSchedulesService.create).mock.calls[0];
    expect(draftId).toBe(readyDraft.id);
    expect(accountId).toBe('account-1');
    expect(scheduledFor).not.toContain('undefined');
    expect(() => new Date(scheduledFor)).not.toThrow();
    expect(Number.isNaN(new Date(scheduledFor).getTime())).toBe(false);
  });

  it('rejects scheduling without a chosen date/time', () => {
    vi.mocked(contentDraftsService.list).mockReturnValue(of([draft('READY', 'READY')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const readyDraft = component['drafts']()[0];

    component['setDraftScheduleAccount'](readyDraft, 'account-1');
    component['scheduleDraft'](readyDraft);

    expect(publishSchedulesService.create).not.toHaveBeenCalled();
    expect(component['scheduleCreateErrors']()[readyDraft.id]).toBe('Choose a valid future date and time.');
  });

  it('shows upcoming schedules with local time, draft title, and TEST labeled as non-real', () => {
    vi.mocked(publishSchedulesService.list).mockReturnValue(of([schedule('SCHEDULED')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('schedule');
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('My draft');
    expect(text).toContain('Scheduled');
    expect(text).toContain('non-real');
    expect(text).not.toContain('undefined');
    expect(text).not.toContain('Invalid Date');
    expect(text).not.toContain('NaN');
  });

  it('cancels a scheduled item', () => {
    vi.mocked(publishSchedulesService.list).mockReturnValue(of([schedule('SCHEDULED')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const upcoming = component['upcomingSchedules']()[0];

    component['cancelSchedule'](upcoming);

    expect(publishSchedulesService.cancel).toHaveBeenCalledWith('schedule-1');
    expect(component['schedules']()[0].status).toBe('CANCELLED');
  });

  it('reschedules an item to a new local time', () => {
    vi.mocked(publishSchedulesService.list).mockReturnValue(of([schedule('SCHEDULED')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const upcoming = component['upcomingSchedules']()[0];

    component['startReschedule'](upcoming);
    component['setRescheduleDateTime'](upcoming, '2026-09-25T09:00');
    component['submitReschedule'](upcoming);

    expect(publishSchedulesService.reschedule).toHaveBeenCalledTimes(1);
    const [scheduleId, scheduledFor] = vi.mocked(publishSchedulesService.reschedule).mock.calls[0];
    expect(scheduleId).toBe('schedule-1');
    expect(Number.isNaN(new Date(scheduledFor).getTime())).toBe(false);
  });

  it('shows dispatched schedules as history, separate from a failed publication state', () => {
    vi.mocked(publishSchedulesService.list).mockReturnValue(of([schedule('DISPATCHED')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('schedule');
    fixture.detectChanges();

    expect(component['upcomingSchedules']().length).toBe(0);
    expect(component['scheduleHistory']().length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Dispatched');
  });

  it('shows a failed schedule with its bounded failure reason', () => {
    vi.mocked(publishSchedulesService.list).mockReturnValue(of([schedule('FAILED')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('schedule');
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('MEDIA_UNAVAILABLE');
    expect(text).toContain('Scheduled media is no longer ready');
  });

  it('shows an error state when the schedule list API fails', () => {
    vi.mocked(publishSchedulesService.list).mockReturnValue(throwError(() => new Error('Network failure')));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('schedule');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Schedules could not be loaded.');
  });

  it('shows the snapshot note for a schedulable draft', () => {
    vi.mocked(contentDraftsService.list).mockReturnValue(of([draft('READY', 'READY')]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('drafts');
    const readyDraft = component['drafts']()[0];
    component['toggleDraft'](readyDraft);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('editing this draft afterward will not change an already-scheduled post');
  });

  function asset(status: MediaAssetStatus): MediaAssetSummary {
    return {
      id: `${status}-asset`,
      sourceType: 'DIRECT_URL',
      sourceUrl: `https://example.com/${status.toLowerCase()}/video.mp4`,
      parentAssetId: null,
      derivationType: 'ORIGINAL',
      status,
      originalFilename: status === 'PENDING' ? null : 'video.mp4',
      contentType: status === 'PENDING' ? null : 'video/mp4',
      fileSizeBytes: status === 'PENDING' ? null : 1_048_576,
      checksumSha256: status === 'READY' ? '0'.repeat(64) : null,
      durationMs: status === 'READY' ? 12_000 : null,
      width: status === 'READY' ? 1920 : null,
      height: status === 'READY' ? 1080 : null,
      videoCodec: status === 'READY' ? 'h264' : null,
      audioCodec: status === 'READY' ? 'aac' : null,
      containerFormat: status === 'READY' ? 'mp4' : null,
      importJobId: `${status}-job`,
      processingJobId: null,
      inspectionStatus: status === 'READY' ? 'INSPECTED' : 'PENDING',
      inspectionJobId: `${status}-inspection-job`,
      inspectionErrorCode: null,
      inspectionErrorMessage: null,
      frameRate: status === 'READY' ? 29.97 : null,
      bitrate: status === 'READY' ? 800_000 : null,
      hasVideo: status === 'READY' ? true : null,
      hasAudio: status === 'READY' ? true : null,
      errorCode: status === 'FAILED' ? 'UNSUPPORTED_MEDIA' : null,
      errorMessage: status === 'FAILED' ? 'Source did not return media content' : null,
      createdAt: '2026-09-10T08:00:00Z',
      updatedAt: '2026-09-10T08:00:10Z',
      readyAt: status === 'READY' ? '2026-09-10T08:00:10Z' : null,
    };
  }

  function clipAsset(): MediaAssetSummary {
    return {
      ...asset('PROCESSING'),
      id: 'clip-asset',
      sourceType: 'DERIVED',
      sourceUrl: 'asset:READY-asset',
      parentAssetId: 'READY-asset',
      derivationType: 'CLIP',
      importJobId: null,
      processingJobId: 'clip-job',
    };
  }

  function verticalAsset(): MediaAssetSummary {
    return {
      ...asset('PROCESSING'),
      id: 'vertical-asset',
      sourceType: 'DERIVED',
      sourceUrl: 'asset:READY-asset',
      parentAssetId: 'READY-asset',
      derivationType: 'SOCIAL_VERTICAL',
      importJobId: null,
      processingJobId: 'vertical-job',
    };
  }

  function analysis(status: HighlightAnalysisSummary['status']): HighlightAnalysisSummary {
    return {
      id: 'analysis-1',
      assetId: 'READY-asset',
      status,
      analysisJobId: 'analysis-job',
      analyzerType: 'DETERMINISTIC_V1',
      analyzerVersion: '1',
      errorCode: status === 'FAILED' ? 'ANALYSIS_FAILED' : null,
      errorMessage: status === 'FAILED' ? 'Highlight analysis failed' : null,
      createdAt: '2026-09-10T08:01:00Z',
      updatedAt: '2026-09-10T08:01:10Z',
      completedAt: status === 'SUCCEEDED' ? '2026-09-10T08:01:10Z' : null,
      candidates: status === 'SUCCEEDED'
        ? [
            {
              id: 'candidate-1',
              analysisId: 'analysis-1',
              assetId: 'READY-asset',
              startMs: 1000,
              endMs: 6000,
              durationMs: 5000,
              score: 0.82,
              reason: 'Deterministic Phase 7A candidate',
              rank: 1,
              createdAt: '2026-09-10T08:01:10Z',
            },
          ]
        : [],
    };
  }

  function testAccount(): SocialAccountSummary {
    return {
      id: 'account-1',
      platform: 'TEST',
      displayName: 'My TEST Account',
      externalAccountId: null,
      status: 'ACTIVE',
      createdAt: '2026-09-10T08:00:00Z',
      updatedAt: '2026-09-10T08:00:00Z',
    };
  }

  function publication(status: PublicationSummary['status']): PublicationSummary {
    return {
      id: 'publication-1',
      assetId: 'READY-asset',
      assetFilename: 'video.mp4',
      socialAccountId: 'account-1',
      socialAccountDisplayName: 'My TEST Account',
      platform: 'TEST',
      status,
      jobId: 'publish-job-1',
      caption: 'Hello from the TEST provider',
      providerRequestId: status === 'PUBLISHED' ? 'test-req-publication-1' : null,
      providerPublicationId: status === 'PUBLISHED' ? 'test-pub-publication-1' : null,
      contentDraftId: null,
      createdAt: '2026-09-10T08:03:00Z',
      updatedAt: '2026-09-10T08:03:10Z',
      publishedAt: status === 'PUBLISHED' ? '2026-09-10T08:03:10Z' : null,
      failureCode: status === 'FAILED' ? 'PUBLISH_MEDIA_FAILED' : null,
      failureMessage: status === 'FAILED' ? 'Publishing failed' : null,
      attempts: [],
    };
  }

  function draft(
    status: ContentDraftSummary['status'],
    workflowStage: ContentDraftSummary['workflowStage'],
  ): ContentDraftSummary {
    return {
      id: 'draft-1',
      sourceAssetId: 'READY-asset',
      mediaAssetId: status === 'DRAFT' ? 'clip-asset' : 'READY-asset',
      mediaAssetFilename: status === 'DRAFT' ? null : 'video.mp4',
      sourceHighlightCandidateId: 'candidate-1',
      title: null,
      caption: null,
      status,
      workflowStage,
      pendingJobId: status === 'DRAFT' ? 'clip-job' : null,
      failureCode: status === 'FAILED' ? 'FFMPEG_ERROR' : null,
      failureMessage: status === 'FAILED' ? 'Encoding failed' : null,
      createdAt: '2026-09-10T08:05:00Z',
      updatedAt: '2026-09-10T08:05:10Z',
      publishedAt: null,
      publications: status === 'PUBLISHING' ? [publication('PENDING')] : [],
      robotRunId: null,
    };
  }

  function schedule(status: PublishScheduleSummary['status']): PublishScheduleSummary {
    return {
      id: 'schedule-1',
      contentDraftId: 'draft-1',
      draftTitle: 'My draft',
      mediaAssetId: 'READY-asset',
      mediaAssetFilename: 'video.mp4',
      socialAccountId: 'account-1',
      socialAccountDisplayName: 'My TEST Account',
      platform: 'TEST',
      captionSnapshot: 'Scheduled caption',
      scheduledFor: '2026-09-20T18:30:00Z',
      status,
      publicationId: status === 'DISPATCHED' ? 'publication-1' : null,
      createdAt: '2026-09-10T08:10:00Z',
      updatedAt: '2026-09-10T08:10:00Z',
      dispatchedAt: status === 'DISPATCHED' ? '2026-09-20T18:30:05Z' : null,
      cancelledAt: status === 'CANCELLED' ? '2026-09-10T08:11:00Z' : null,
      failureCode: status === 'FAILED' ? 'MEDIA_UNAVAILABLE' : null,
      failureMessage: status === 'FAILED' ? 'Scheduled media is no longer ready' : null,
      dispatchDelayMs: status === 'DISPATCHED' ? 5000 : null,
    };
  }

  function transcript(status: MediaTranscriptSummary['status']): MediaTranscriptSummary {
    return {
      id: 'transcript-1',
      assetId: 'READY-asset',
      status,
      transcriptionJobId: 'transcription-job',
      provider: 'LOCAL_WHISPER_CLI',
      model: 'base',
      detectedLanguage: status === 'SUCCEEDED' ? 'en' : null,
      durationMs: status === 'SUCCEEDED' ? 12_000 : null,
      errorCode: status === 'FAILED' ? 'TRANSCRIPTION_FAILED' : null,
      errorMessage: status === 'FAILED' ? 'Transcription failed' : null,
      createdAt: '2026-09-10T08:02:00Z',
      startedAt: status === 'PENDING' ? null : '2026-09-10T08:02:01Z',
      completedAt: status === 'SUCCEEDED' ? '2026-09-10T08:02:10Z' : null,
      updatedAt: '2026-09-10T08:02:10Z',
      segments: status === 'SUCCEEDED'
        ? [
            {
              id: 'segment-1',
              sequence: 1,
              startMs: 0,
              endMs: 2_000,
              text: 'Hello world',
              confidence: null,
            },
          ]
        : [],
    };
  }
});
