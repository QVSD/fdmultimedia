import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { MediaAssetSummary, MediaAssetStatus } from '../../core/assets/asset.models';
import { Content } from './content';

describe('Content', () => {
  let component: Content;
  let fixture: ComponentFixture<Content>;
  let assetsService: Pick<AssetsService, 'list' | 'importUrl'>;

  beforeEach(async () => {
    assetsService = {
      list: vi.fn().mockReturnValue(of([
        asset('PENDING'),
        asset('IMPORTING'),
        asset('READY'),
        asset('FAILED'),
      ])),
      importUrl: vi.fn().mockReturnValue(of({ asset: asset('PENDING') })),
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

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('PENDING');
    expect(text).toContain('IMPORTING');
    expect(text).toContain('READY');
    expect(text).toContain('FAILED');
    expect(text).toContain('video.mp4');
    expect(text).toContain('video/mp4');
    expect(text).toContain('1920x1080');
    expect(text).toContain('h264 / aac');
    expect(text).toContain('Inspected');
    expect(text).toContain('UNSUPPORTED_MEDIA');
  });

  it('renders empty state', async () => {
    vi.mocked(assetsService.list).mockReturnValue(of([]));
    fixture = TestBed.createComponent(Content);
    component = fixture.componentInstance;
    await fixture.whenStable();

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No media assets have been imported yet.');
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

      expect(fixture.nativeElement.textContent).toContain('READY');
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

  function asset(status: MediaAssetStatus): MediaAssetSummary {
    return {
      id: `${status}-asset`,
      sourceType: 'DIRECT_URL',
      sourceUrl: `https://example.com/${status.toLowerCase()}/video.mp4`,
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
});
