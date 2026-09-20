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
import {
  DashboardBreakdown, DashboardDimension, DashboardFilters, DashboardMetric,
  DashboardOptions, DashboardSummary, DashboardTrend, DashboardWindow,
} from '../../core/publishing/publication-dashboard.models';

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
  protected readonly dashboardLoading = signal(true);
  protected readonly dashboardError = signal<string | null>(null);
  protected readonly summary = signal<DashboardSummary | null>(null);
  protected readonly trend = signal<DashboardTrend | null>(null);
  protected readonly breakdown = signal<DashboardBreakdown | null>(null);
  protected readonly options = signal<DashboardOptions | null>(null);
  protected readonly filters = signal<DashboardFilters>({});
  protected readonly dimension = signal<DashboardDimension>('ROBOT');
  protected readonly trendMetric = signal<DashboardMetric>('VIEWS');
  protected readonly windows: DashboardWindow[] = ['LATEST', 'H24', 'H72', 'D7'];
  protected readonly dimensions: DashboardDimension[] = ['ROBOT', 'PERSONA', 'CONTENT_SOURCE', 'PROVIDER', 'ORIGIN', 'AI_USAGE'];
  protected readonly trendMetrics: DashboardMetric[] = ['VIEWS', 'REACH', 'LIKES', 'COMMENTS', 'SHARES', 'SAVES', 'TOTAL_INTERACTIONS'];
  private readonly subscriptions = new Subscription();
  private dashboardSubscription?: Subscription;

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
      const window = this.valid(this.windows, params.get('window'), 'LATEST');
      const dimension = this.valid(this.dimensions, params.get('dimension'), 'ROBOT') ?? 'ROBOT';
      const metric = this.valid(this.trendMetrics, params.get('metric'), 'VIEWS') ?? 'VIEWS';
      const date = (value: string | null): string | undefined => {
        if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined;
        const parsed = new Date(`${value}T00:00:00Z`);
        return Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value ? undefined : value;
      };
      const dateFrom = date(params.get('dateFrom'));
      const dateTo = date(params.get('dateTo'));
      const today = new Date().toISOString().slice(0, 10);
      const from = new Date();
      from.setUTCDate(from.getUTCDate() - 29);
      const safeFrom = dateFrom ?? from.toISOString().slice(0, 10);
      const safeTo = dateTo ?? today;
      const validRange = safeFrom <= safeTo && safeTo <= today &&
        (Date.parse(`${safeTo}T00:00:00Z`) - Date.parse(`${safeFrom}T00:00:00Z`)) <= 364 * 86400000;
      const next: DashboardFilters = {
        dateFrom: validRange ? safeFrom : from.toISOString().slice(0, 10),
        dateTo: validRange ? safeTo : today, window,
        provider: this.valid(['TEST', 'INSTAGRAM'] as const, params.get('provider'), undefined),
        robotId: this.safeId(params.get('robotId')),
        personaId: this.safeId(params.get('personaId')),
        contentSourceId: this.safeId(params.get('contentSourceId')),
        origin: this.valid(['MANUAL', 'ROBOT'] as const, params.get('origin'), undefined),
        aiUsage: this.valid(['AI_APPLIED', 'NO_APPLIED_AI'] as const, params.get('aiUsage'), undefined),
      };
      this.filters.set(next);
      this.dimension.set(dimension);
      this.trendMetric.set(metric);
      this.loadDashboard(next, dimension, metric);
    }));
  }

  ngOnDestroy(): void { this.dashboardSubscription?.unsubscribe(); this.subscriptions.unsubscribe(); }

  protected select(id: string): void {
    this.router.navigate(['/analytics'], { queryParams: { publicationId: id || null }, queryParamsHandling: 'merge' });
  }

  protected filter(name: keyof DashboardFilters, value: string): void {
    this.router.navigate(['/analytics'], { queryParams: { [name]: value || null }, queryParamsHandling: 'merge' });
  }

  protected chooseDimension(value: string): void {
    this.router.navigate(['/analytics'], { queryParams: { dimension: value }, queryParamsHandling: 'merge' });
  }

  protected chooseMetric(value: string): void {
    this.router.navigate(['/analytics'], { queryParams: { metric: value }, queryParamsHandling: 'merge' });
  }

  private loadDashboard(filters: DashboardFilters, dimension: DashboardDimension, metric: DashboardMetric): void {
    this.dashboardSubscription?.unsubscribe();
    this.dashboardLoading.set(true);
    this.dashboardError.set(null);
    this.dashboardSubscription = forkJoin({
      summary: this.analytics.dashboardSummary(filters),
      trend: this.analytics.dashboardTrend(filters, metric),
      breakdown: this.analytics.dashboardBreakdown(filters, dimension),
      options: this.analytics.dashboardOptions(filters),
    }).subscribe({
      next: ({ summary, trend, breakdown, options }) => {
        this.summary.set(summary);
        this.trend.set(trend);
        this.breakdown.set(breakdown);
        this.options.set(options);
        this.dashboardLoading.set(false);
      },
      error: () => {
        this.dashboardError.set('Dashboard could not be loaded.');
        this.dashboardLoading.set(false);
      },
    });
  }

  protected reloadDashboard(): void {
    this.loadDashboard(this.filters(), this.dimension(), this.trendMetric());
  }

  private valid<T extends string>(values: readonly T[], value: string | null, fallback: T | undefined): T | undefined {
    return value && values.includes(value as T) ? value as T : fallback;
  }

  private safeId(value: string | null): string | undefined {
    return value && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value) ? value : undefined;
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

  protected decimal(value: number | null | undefined): string {
    return value == null ? '—' : value.toLocaleString(undefined, { maximumFractionDigits: 1 });
  }

  protected bar(value: number | null, maximum: number): number {
    return value == null || maximum <= 0 ? 0 : Math.max(0, Math.min(100, 100 * value / maximum));
  }

  protected trendMax(): number {
    return Math.max(0, ...(this.trend()?.points.map((point) => point.metric.average ?? 0) ?? []));
  }

  protected age(seconds: number): string {
    if (!Number.isFinite(seconds) || seconds < 0) return '—';
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
    return `${Math.floor(seconds / 86400)}d`;
  }
}
