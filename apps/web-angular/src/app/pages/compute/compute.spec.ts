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
      lastSeenAt: '2026-09-09T12:00:00Z',
      registeredAt: '2026-09-09T12:00:00Z',
    };
  }
});
