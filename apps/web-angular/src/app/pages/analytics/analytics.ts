import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, forkJoin } from 'rxjs';

import { PublishingService } from '../../core/publishing/publishing.service';
import { PublicationSummary } from '../../core/publishing/publishing.models';
import { PublicationAnalyticsService } from '../../core/publishing/publication-analytics.service';
import {
  PublicationAnalyticsSnapshot, PublicationAnalyticsState, PublicationAttribution,
} from '../../core/publishing/publication-analytics.models';

@Component({
  selector: 'app-analytics',
  imports: [DatePipe, RouterLink],
  templateUrl: './analytics.html',
  styleUrl: './analytics.scss',
})
export class Analytics implements OnInit, OnDestroy {
  protected readonly publications = signal<PublicationSummary[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly history = signal<PublicationAnalyticsSnapshot[]>([]);
  protected readonly attribution = signal<PublicationAttribution | null>(null);
  protected readonly collectionState = signal<PublicationAnalyticsState | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly refreshing = signal(false);
  protected readonly refreshMessage = signal<string | null>(null);
  private readonly subscriptions = new Subscription();

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly publishing: PublishingService,
    private readonly analytics: PublicationAnalyticsService,
  ) {}

  ngOnInit(): void {
    this.subscriptions.add(this.publishing.list().subscribe({
      next: (rows) => this.publications.set(rows.filter((row) => row.status === 'PUBLISHED').slice(0, 50)),
      error: () => this.error.set('Published items could not be loaded.'),
    }));
    this.subscriptions.add(this.route.queryParamMap.subscribe((params) => {
      const id = params.get('publicationId');
      this.selectedId.set(id);
      if (id) this.load(id);
      else this.loading.set(false);
    }));
  }

  ngOnDestroy(): void { this.subscriptions.unsubscribe(); }

  protected select(id: string): void {
    this.router.navigate(['/analytics'], { queryParams: { publicationId: id } });
  }

  protected load(id: string): void {
    this.loading.set(true);
    this.error.set(null);
    this.subscriptions.add(forkJoin({
      history: this.analytics.history(id),
      attribution: this.analytics.attribution(id),
      state: this.analytics.state(id),
    }).subscribe({
      next: ({ history, attribution, state }) => {
        this.history.set(history);
        this.attribution.set(attribution);
        this.collectionState.set(state);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Publication analytics could not be loaded.');
        this.loading.set(false);
      },
    }));
  }

  protected refresh(): void {
    const id = this.selectedId();
    if (!id || this.refreshing()) return;
    this.refreshing.set(true);
    this.refreshMessage.set(null);
    this.subscriptions.add(this.analytics.refresh(id).subscribe({
      next: () => {
        this.refreshing.set(false);
        this.refreshMessage.set('Analytics updated.');
        this.load(id);
      },
      error: (response: HttpErrorResponse) => {
        this.refreshing.set(false);
        this.refreshMessage.set(response.status === 429
          ? 'Please wait before refreshing analytics again.'
          : response.status === 503 ? 'Analytics collection is disabled.' : 'Analytics refresh failed.');
        this.subscriptions.add(this.analytics.state(id).subscribe({
          next: (state) => this.collectionState.set(state), error: () => {},
        }));
      },
    }));
  }

  protected metric(value: number | null | undefined): string {
    return value == null ? '—' : value.toLocaleString();
  }

  protected age(seconds: number): string {
    if (!Number.isFinite(seconds) || seconds < 0) return '—';
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
    return `${Math.floor(seconds / 86400)}d`;
  }
}
