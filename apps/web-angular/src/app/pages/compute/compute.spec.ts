import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { WorkerSummary } from '../../core/workers/worker.models';
import { WorkersService } from '../../core/workers/workers.service';
import { SchedulingService } from '../../core/scheduling/scheduling.service';
import { Compute } from './compute';

describe('Compute', () => {
  let component: Compute;
  let fixture: ComponentFixture<Compute>;
  let workersService: Pick<WorkersService, 'list'>;
  let schedulingService: Pick<SchedulingService, 'overview' | 'workers' | 'decisions'>;

  beforeEach(async () => {
    workersService = {
      list: vi.fn().mockReturnValue(of([worker()])),
    };
    schedulingService = {
      overview: vi.fn().mockReturnValue(of({
        window: '24h',
        queue: { queued: 2, assigned: 0, running: 1, succeeded: 4, failed: 1, oldestQueuedAgeMs: 3200 },
        execution: [{ jobType: 'SYSTEM_TEST', attempts: 5, successes: 4, failures: 1, averageQueueWaitMs: 420, averageExecutionMs: 3200, averageTotalLatencyMs: 3700 }],
        scheduling: { claims: 5, fallbackClaims: 1, starvationOverrideClaims: 0 },
      })),
      workers: vi.fn().mockReturnValue(of([{
        workerId: worker().id, workerName: 'Node A', jobType: 'SYSTEM_TEST', attempts: 5, successes: 4,
        failures: 1, averageQueueWaitMs: 420, averageExecutionMs: 3200, averageTotalLatencyMs: 3700,
        mostRecentExecutionAt: '2026-09-09T12:00:00Z',
      }])),
      decisions: vi.fn().mockReturnValue(of([{
        timestamp: '2026-09-09T12:00:00Z', jobId: 'job-1', jobType: 'SYSTEM_TEST', workerId: worker().id,
        workerName: 'Node A', policy: 'TELEMETRY_AWARE_V1', suitabilityScore: 120, telemetryFresh: false,
        fallbackUsed: true, starvationOverride: false, reasonCodes: ['TELEMETRY_STALE_FIFO'], activeJobs: 0,
        maxActiveJobs: 1, attempt: 1,
      }])),
    };

    await TestBed.configureTestingModule({
      imports: [Compute],
      providers: [
        { provide: WorkersService, useValue: workersService },
        { provide: SchedulingService, useValue: schedulingService },
      ],
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
    expect(text).toContain('Scheduling & performance');
    expect(text).toContain('3.2 s');
    expect(text).toContain('Fallback');
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
