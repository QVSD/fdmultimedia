import { DatePipe, JsonPipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription, finalize, interval, startWith, switchMap } from 'rxjs';

import { JobSummary } from '../../core/jobs/job.models';
import { JobsService } from '../../core/jobs/jobs.service';

type LoadState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-jobs',
  imports: [DatePipe, FormsModule, JsonPipe],
  templateUrl: './jobs.html',
  styleUrl: './jobs.scss',
})
export class Jobs implements OnInit, OnDestroy {
  protected readonly jobs = signal<JobSummary[]>([]);
  protected readonly loadState = signal<LoadState>('loading');
  protected readonly message = signal('Hello worker');
  protected readonly durationMs = signal(2000);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  private subscription?: Subscription;

  constructor(private readonly jobsService: JobsService) {}

  ngOnInit(): void {
    this.subscription = interval(3000)
      .pipe(
        startWith(0),
        switchMap(() => this.jobsService.list()),
      )
      .subscribe({
        next: (jobs) => {
          this.jobs.set(jobs);
          this.loadState.set('ready');
        },
        error: () => this.loadState.set('error'),
      });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  protected createJob(): void {
    this.createError.set(null);
    const message = this.message().trim();
    const durationMs = Number(this.durationMs());
    if (!message || durationMs < 0 || durationMs > 10000) {
      this.createError.set('Message is required and duration must be between 0 and 10000 ms.');
      return;
    }

    this.creating.set(true);
    this.jobsService
      .createSystemTest(message, durationMs)
      .pipe(finalize(() => this.creating.set(false)))
      .subscribe({
        next: (job) => {
          this.jobs.set([job, ...this.jobs().filter((existing) => existing.id !== job.id)]);
          this.message.set('Hello worker');
          this.durationMs.set(2000);
          this.loadState.set('ready');
        },
        error: () => this.createError.set('Job could not be created.'),
      });
  }

  protected payloadMessage(job: JobSummary): string {
    return String(job.payload['message'] ?? '');
  }
}
