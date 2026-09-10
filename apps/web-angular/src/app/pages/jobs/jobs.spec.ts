import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { JobSummary } from '../../core/jobs/job.models';
import { JobsService } from '../../core/jobs/jobs.service';
import { Jobs } from './jobs';

describe('Jobs', () => {
  let component: Jobs;
  let fixture: ComponentFixture<Jobs>;
  let jobsService: Pick<JobsService, 'list' | 'createSystemTest'>;

  beforeEach(async () => {
    jobsService = {
      list: vi.fn().mockReturnValue(of([job('QUEUED'), job('RUNNING'), job('SUCCEEDED'), job('FAILED')])),
      createSystemTest: vi.fn().mockReturnValue(of(job('QUEUED'))),
    };

    await TestBed.configureTestingModule({
      imports: [Jobs],
      providers: [{ provide: JobsService, useValue: jobsService }],
    }).compileComponents();

    fixture = TestBed.createComponent(Jobs);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders job lifecycle states', () => {
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('QUEUED');
    expect(text).toContain('RUNNING');
    expect(text).toContain('SUCCEEDED');
    expect(text).toContain('FAILED');
    expect(text).toContain('Node A');
  });

  it('renders empty state', async () => {
    vi.mocked(jobsService.list).mockReturnValue(of([]));
    fixture = TestBed.createComponent(Jobs);
    component = fixture.componentInstance;
    await fixture.whenStable();

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No jobs have been created yet.');
  });

  it('renders API error state', async () => {
    vi.mocked(jobsService.list).mockReturnValue(throwError(() => new Error('Network failure')));
    fixture = TestBed.createComponent(Jobs);
    component = fixture.componentInstance;
    await fixture.whenStable();

    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Jobs could not be loaded.');
  });

  it('keeps polling after a transient API error', async () => {
    vi.useFakeTimers();
    fixture.destroy();
    vi.mocked(jobsService.list)
      .mockReset()
      .mockReturnValueOnce(throwError(() => new Error('Network failure')))
      .mockReturnValueOnce(of([job('SUCCEEDED')]));
    fixture = TestBed.createComponent(Jobs);
    component = fixture.componentInstance;

    try {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Jobs could not be loaded.');

      await vi.advanceTimersByTimeAsync(3000);
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('SUCCEEDED');
      expect(jobsService.list).toHaveBeenCalledTimes(2);
    } finally {
      fixture.destroy();
      vi.useRealTimers();
    }
  });

  it('creates a SYSTEM_TEST job', () => {
    fixture.detectChanges();

    component['message'].set('Hello phase 4');
    component['durationMs'].set(1500);
    component['createJob']();

    expect(jobsService.createSystemTest).toHaveBeenCalledWith('Hello phase 4', 1500);
  });

  function job(status: JobSummary['status']): JobSummary {
    return {
      id: `${status}-job`,
      type: 'SYSTEM_TEST',
      status,
      payload: { message: `message ${status}`, durationMs: 100 },
      result: status === 'SUCCEEDED' ? { message: 'done', workerName: 'Node A' } : null,
      errorCode: status === 'FAILED' ? 'SYSTEM_TEST_FAILED' : null,
      errorMessage: status === 'FAILED' ? 'Synthetic failure' : null,
      assignedWorkerId: status === 'QUEUED' ? null : 'worker-id',
      assignedWorkerName: status === 'QUEUED' ? null : 'Node A',
      attemptCount: status === 'QUEUED' ? 0 : 1,
      maxAttempts: 3,
      queuedAt: '2026-09-10T05:00:00Z',
      assignedAt: status === 'QUEUED' ? null : '2026-09-10T05:00:01Z',
      startedAt: status === 'RUNNING' || status === 'SUCCEEDED' || status === 'FAILED'
        ? '2026-09-10T05:00:02Z'
        : null,
      finishedAt: status === 'SUCCEEDED' || status === 'FAILED' ? '2026-09-10T05:00:03Z' : null,
      leaseExpiresAt: status === 'ASSIGNED' || status === 'RUNNING' ? '2026-09-10T05:00:30Z' : null,
      createdAt: '2026-09-10T05:00:00Z',
      updatedAt: '2026-09-10T05:00:03Z',
    };
  }
});
