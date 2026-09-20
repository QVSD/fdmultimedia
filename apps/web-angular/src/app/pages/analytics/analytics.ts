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
import {
  ComparisonResult, InsightsResponse, InsightStatistic,
} from '../../core/publishing/publication-insights.models';

type AnalyticsTab = 'dashboard' | 'insights';

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
  protected readonly tab = signal<AnalyticsTab>('dashboard');
  protected readonly insightsLoading = signal(true);
  protected readonly insightsError = signal<string | null>(null);
  protected readonly insights = signal<InsightsResponse | null>(null);
  protected readonly compareDimension = signal<DashboardDimension>('ORIGIN');
  protected readonly compareLeft = signal('');
  protected readonly compareRight = signal('');
  protected readonly compareMetric = signal<DashboardMetric>('VIEWS');
  protected readonly compareStatistic = signal<InsightStatistic>('MEDIAN');
  protected readonly compareResult = signal<ComparisonResult | null>(null);
  protected readonly compareLoading = signal(false);
  protected readonly compareError = signal<string | null>(null);
  private readonly subscriptions = new Subscription();
  private dashboardSubscription?: Subscription;
  private insightsSubscription?: Subscription;

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
      this.loadInsights(next);

      const tab = this.valid(['dashboard', 'insights'] as const, params.get('tab'), 'dashboard') ?? 'dashboard';
      this.tab.set(tab);
      const compareDimension = this.valid(this.dimensions, params.get('compareDimension'), 'ORIGIN') ?? 'ORIGIN';
      const compareMetric = this.valid(this.trendMetrics, params.get('compareMetric'), 'VIEWS') ?? 'VIEWS';
      const compareStatistic = this.valid(['AVERAGE', 'MEDIAN'] as const, params.get('compareStatistic'), 'MEDIAN') ?? 'MEDIAN';
      const compareLeft = params.get('compareLeft') ?? '';
      const compareRight = params.get('compareRight') ?? '';
      this.compareDimension.set(compareDimension);
      this.compareMetric.set(compareMetric);
      this.compareStatistic.set(compareStatistic);
      this.compareLeft.set(compareLeft);
      this.compareRight.set(compareRight);
      if (compareLeft && compareRight) {
        this.runCompare(next);
      } else {
        this.compareResult.set(null);
      }
    }));
  }

  ngOnDestroy(): void {
    this.dashboardSubscription?.unsubscribe();
    this.insightsSubscription?.unsubscribe();
    this.subscriptions.unsubscribe();
  }

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

  protected switchTab(tab: AnalyticsTab): void {
    this.router.navigate(['/analytics'], { queryParams: { tab }, queryParamsHandling: 'merge' });
  }

  protected setCompareField(name: 'compareDimension' | 'compareMetric' | 'compareStatistic' | 'compareLeft' | 'compareRight', value: string): void {
    const reset = name === 'compareDimension' ? { compareLeft: null, compareRight: null } : {};
    this.router.navigate(['/analytics'], { queryParams: { [name]: value, ...reset }, queryParamsHandling: 'merge' });
  }

  protected runCompareFromForm(): void {
    this.router.navigate(['/analytics'], {
      queryParams: {
        compareDimension: this.compareDimension(), compareMetric: this.compareMetric(),
        compareStatistic: this.compareStatistic(), compareLeft: this.compareLeft(), compareRight: this.compareRight(),
      },
      queryParamsHandling: 'merge',
    });
  }

  private runCompare(filters: DashboardFilters): void {
    if (this.compareLeft() === this.compareRight()) {
      this.compareError.set('Choose two different segments to compare.');
      this.compareResult.set(null);
      return;
    }
    this.compareLoading.set(true);
    this.compareError.set(null);
    this.subscriptions.add(this.analytics.compareSegments({
      ...filters, dimension: this.compareDimension(), leftSegmentId: this.compareLeft(),
      rightSegmentId: this.compareRight(), metric: this.compareMetric(), statistic: this.compareStatistic(),
    }).subscribe({
      next: (result) => {
        this.compareResult.set(result);
        this.compareLoading.set(false);
      },
      error: () => {
        this.compareError.set('Comparison could not be loaded.');
        this.compareResult.set(null);
        this.compareLoading.set(false);
      },
    }));
  }

  private loadInsights(filters: DashboardFilters): void {
    this.insightsSubscription?.unsubscribe();
    this.insightsLoading.set(true);
    this.insightsError.set(null);
    this.insightsSubscription = this.analytics.insights(filters).subscribe({
      next: (response) => {
        this.insights.set(response);
        this.insightsLoading.set(false);
      },
      error: () => {
        this.insightsError.set('Insights could not be loaded.');
        this.insightsLoading.set(false);
      },
    });
  }

  protected segmentOptions(dimension: DashboardDimension): { value: string; label: string }[] {
    const opts = this.options();
    switch (dimension) {
      case 'ROBOT':
        return [{ value: 'NONE', label: 'Manual / No Robot' }, ...(opts?.robots ?? []).map((o) => ({ value: o.id, label: o.label }))];
      case 'PERSONA':
        return [{ value: 'NONE', label: 'No applied Persona' }, ...(opts?.personas ?? []).map((o) => ({ value: o.id, label: o.label }))];
      case 'CONTENT_SOURCE':
        return [{ value: 'NONE', label: 'No ContentSource' }, ...(opts?.contentSources ?? []).map((o) => ({ value: o.id, label: o.label }))];
      case 'PROVIDER':
        return (opts?.providers ?? []).map((p) => ({ value: p, label: p }));
      case 'ORIGIN':
        return [{ value: 'MANUAL', label: 'Manual' }, { value: 'ROBOT', label: 'Robot' }];
      case 'AI_USAGE':
        return [{ value: 'AI_APPLIED', label: 'Applied AI suggestion' }, { value: 'NO_APPLIED_AI', label: 'No applied AI suggestion' }];
    }
  }

  protected directionLabel(direction: string | null): string {
    switch (direction) {
      case 'HIGHER_OBSERVED': return 'Higher observed';
      case 'LOWER_OBSERVED': return 'Lower observed';
      case 'SIMILAR_OBSERVED': return 'Similar observed';
      default: return '—';
    }
  }

  protected resultTypeLabel(type: string): string {
    switch (type) {
      case 'INSUFFICIENT_SAMPLE': return 'Not enough observations yet';
      case 'LOW_COVERAGE': return 'Analytics coverage too low';
      case 'TOO_YOUNG': return 'Too young to observe';
      case 'METRIC_UNAVAILABLE': return 'Metric unavailable';
      default: return 'Observation';
    }
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
