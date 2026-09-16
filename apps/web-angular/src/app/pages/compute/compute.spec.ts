import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { WorkerSummary } from '../../core/workers/worker.models';
import { WorkersService } from '../../core/workers/workers.service';
import { Compute } from './compute';

describe('Compute', () => {
  let component: Compute;
  let fixture: ComponentFixture<Compute>;
  let workersService: Pick<WorkersService, 'list'>;

  beforeEach(async () => {
    workersService = {
      list: vi.fn().mockReturnValue(of([worker()])),
    };

    await TestBed.configureTestingModule({
      imports: [Compute],
      providers: [{ provide: WorkersService, useValue: workersService }],
    }).compileComponents();

    fixture = TestBed.createComponent(Compute);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders registered workers', () => {
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Node A');
    expect(text).toContain('ONLINE');
    expect(text).toContain('1 online');
    expect(text).toContain('Windows 11 / amd64');
    expect(text).toContain('23% · 16 logical cores');
    expect(text).toContain('9.2 GiB available / 32 GiB');
    expect(text).toContain('1 / 1 active');
    expect(text).toContain('At capacity');
    expect(text).toContain('TELEMETRY_AWARE_V1');
    expect(text).toContain('CREATE_CLIP');
    expect(text).toContain('Highlights: TRANSCRIPT_SEMANTIC_V1');
  });

  it('renders stale or unavailable telemetry without showing old values as current', async () => {
    vi.mocked(workersService.list).mockReturnValue(of([
      {
        ...worker(),
        telemetry: {
          systemCpuLoad: null,
          processCpuLoad: null,
          availableMemoryBytes: null,
          jvmHeapUsedBytes: null,
          jvmHeapMaxBytes: null,
          activeJobs: null,
          lastTelemetryAt: '2026-09-09T11:00:00Z',
          fresh: false,
        },
      },
    ]));
    fixture = TestBed.createComponent(Compute);
    component = fixture.componentInstance;
    await fixture.whenStable();

    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Telemetry stale');
    expect(text).toContain('Unknown');
  });

  it('renders empty state', async () => {
    vi.mocked(workersService.list).mockReturnValue(of([]));
    fixture = TestBed.createComponent(Compute);
    component = fixture.componentInstance;
    await fixture.whenStable();

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No worker nodes are registered yet.');
  });

  it('renders error state', async () => {
    vi.mocked(workersService.list).mockReturnValue(throwError(() => new Error('Network failure')));
    fixture = TestBed.createComponent(Compute);
    component = fixture.componentInstance;
    await fixture.whenStable();

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Compute nodes could not be loaded.');
  });

  function worker(): WorkerSummary {
    return {
      id: '7c74dd34-f875-41cc-a0d7-689f25587444',
      name: 'Node A',
      status: 'ONLINE',
      machineIdentifier: 'machine-1',
      operatingSystem: 'Windows 11',
      architecture: 'amd64',
      cpuModel: 'AMD Ryzen',
      cpuLogicalCores: 16,
      totalMemoryBytes: 34_359_738_368,
      gpuModel: null,
      gpuMemoryBytes: null,
      agentVersion: 'fdm-worker/0.1.0',
      maxActiveJobs: 1,
      schedulingPolicy: 'TELEMETRY_AWARE_V1',
      schedulingState: 'AT_CAPACITY',
      supportedJobTypes: ['SYSTEM_TEST', 'CREATE_CLIP'],
      supportedHighlightAnalyzers: ['DETERMINISTIC_V1', 'TRANSCRIPT_SEMANTIC_V1'],
      telemetry: {
        systemCpuLoad: 0.23,
        processCpuLoad: 0.11,
        availableMemoryBytes: 9_878_565_888,
        jvmHeapUsedBytes: 134_217_728,
        jvmHeapMaxBytes: 536_870_912,
        activeJobs: 1,
        lastTelemetryAt: '2026-09-09T12:00:00Z',
        fresh: true,
      },
      lastSeenAt: '2026-09-09T12:00:00Z',
      registeredAt: '2026-09-09T12:00:00Z',
    };
  }
});
