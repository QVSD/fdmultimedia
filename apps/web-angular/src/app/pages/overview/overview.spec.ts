import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Overview } from './overview';

describe('Overview', () => {
  let component: Overview;
  let fixture: ComponentFixture<Overview>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Overview],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(Overview);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create', () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/health').flush({ status: 'UP', service: 'media-platform-api' });
    httpMock.expectOne('/api/assets').flush([]);
    httpMock.expectOne('/api/workers').flush([]);
    httpMock.expectOne('/api/jobs').flush([]);

    expect(component).toBeTruthy();
  });

  it('reports the backend as online when the health check succeeds', () => {
    fixture.detectChanges();

    httpMock.expectOne('/api/health').flush({ status: 'UP', service: 'media-platform-api' });
    httpMock.expectOne('/api/assets').flush([]);
    httpMock.expectOne('/api/workers').flush([]);
    httpMock.expectOne('/api/jobs').flush([]);

    expect(component['backendStatus']()).toBe('online');
  });

  it('reports the backend as offline when the health check fails', () => {
    fixture.detectChanges();

    httpMock.expectOne('/api/health').error(new ProgressEvent('network error'));
    httpMock.expectOne('/api/assets').flush([]);
    httpMock.expectOne('/api/workers').flush([]);
    httpMock.expectOne('/api/jobs').flush([]);

    expect(component['backendStatus']()).toBe('offline');
  });

  it('renders product dashboard metrics from existing APIs', () => {
    fixture.detectChanges();

    httpMock.expectOne('/api/health').flush({ status: 'UP', service: 'media-platform-api' });
    httpMock.expectOne('/api/assets').flush([
      {
        id: 'asset-1',
        sourceType: 'DIRECT_URL',
        sourceUrl: 'https://example.com/video.mp4',
        parentAssetId: null,
        derivationType: 'ORIGINAL',
        status: 'READY',
        originalFilename: 'video.mp4',
        contentType: 'video/mp4',
        fileSizeBytes: 1000,
        checksumSha256: null,
        durationMs: 4000,
        width: 1280,
        height: 720,
        videoCodec: 'h264',
        audioCodec: 'aac',
        containerFormat: 'mp4',
        importJobId: null,
        processingJobId: null,
        inspectionStatus: 'INSPECTED',
        inspectionJobId: null,
        inspectionErrorCode: null,
        inspectionErrorMessage: null,
        frameRate: null,
        bitrate: null,
        hasVideo: true,
        hasAudio: true,
        errorCode: null,
        errorMessage: null,
        createdAt: '2026-09-10T08:00:00Z',
        updatedAt: '2026-09-10T08:00:00Z',
        readyAt: '2026-09-10T08:00:00Z',
      },
    ]);
    httpMock.expectOne('/api/workers').flush([
      {
        id: 'worker-1',
        name: 'Worker',
        status: 'ONLINE',
        machineIdentifier: 'machine',
        operatingSystem: 'Windows',
        architecture: 'amd64',
        cpuModel: 'CPU',
        cpuLogicalCores: 8,
        totalMemoryBytes: 1000,
        gpuModel: null,
        gpuMemoryBytes: null,
        agentVersion: '0.1.0',
        supportedJobTypes: ['SYSTEM_TEST'],
        supportedHighlightAnalyzers: ['DETERMINISTIC_V1'],
        telemetry: {
          systemCpuLoad: null,
          processCpuLoad: null,
          availableMemoryBytes: null,
          jvmHeapUsedBytes: null,
          jvmHeapMaxBytes: null,
          activeJobs: 0,
          lastTelemetryAt: '2026-09-10T08:00:00Z',
          fresh: true,
        },
        lastSeenAt: '2026-09-10T08:00:00Z',
        registeredAt: '2026-09-10T08:00:00Z',
      },
    ]);
    httpMock.expectOne('/api/jobs').flush([
      {
        id: 'job-1',
        type: 'SYSTEM_TEST',
        status: 'RUNNING',
        payload: {},
        result: null,
        errorCode: null,
        errorMessage: null,
        assignedWorkerId: null,
        assignedWorkerName: null,
        attemptCount: 1,
        maxAttempts: 3,
        queuedAt: '2026-09-10T08:00:00Z',
        assignedAt: null,
        startedAt: null,
        finishedAt: null,
        leaseExpiresAt: null,
        createdAt: '2026-09-10T08:00:00Z',
        updatedAt: '2026-09-10T08:00:00Z',
      },
    ]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Content workspace status');
    expect(text).toContain('1 online');
    expect(text).toContain('1 ready');
    expect(text).toContain('video.mp4');
    expect(text).not.toContain('Phase 6A');
  });
});
