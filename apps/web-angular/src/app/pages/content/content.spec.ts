import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { HighlightAnalysisSummary, MediaAssetSummary, MediaAssetStatus, MediaTranscriptSummary } from '../../core/assets/asset.models';
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

    await TestBed.configureTestingModule({
      imports: [Content],
      providers: [{ provide: AssetsService, useValue: assetsService }],
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

  it('creates a clip from a highlight candidate', () => {
    fixture.detectChanges();
    const ready = component['assets']().find((item) => item.status === 'READY')!;
    const candidate = analysis('SUCCEEDED').candidates[0];

    component['createClipFromCandidate'](candidate);

    expect(assetsService.createClipFromHighlightCandidate).toHaveBeenCalledWith(candidate.id);
    expect(component['assets']()[0].derivationType).toBe('CLIP');
    expect(component['highlightAnalysis'](ready)?.candidates[0].rank).toBe(1);
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
