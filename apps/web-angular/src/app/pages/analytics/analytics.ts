import { DatePipe, JsonPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, forkJoin } from 'rxjs';
import { PersonasService } from '../../core/personas/personas.service';
import { PersonaSummary } from '../../core/personas/persona.models';

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
import {
  CampaignCohortComparison, CampaignDimension, CampaignOption, CampaignReview, CampaignWindow,
} from '../../core/publishing/campaign-performance.models';
import { OptimizationEligibility, OptimizationProposal, OptimizationProposalOrigin } from '../../core/publishing/optimization-proposal.models';
import { AdaptiveGuardrailEvaluation, RobotChangeEligibility, RobotChangeProposal } from '../../core/publishing/robot-change-proposal.models';
import { RobotsService } from '../../core/robots/robots.service';
import { RobotSummary } from '../../core/robots/robot.models';

type AnalyticsTab = 'dashboard' | 'insights' | 'campaigns';

@Component({
  selector: 'app-analytics',
  imports: [DatePipe, JsonPipe, RouterLink],
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
  protected readonly campaignOptions = signal<CampaignOption[]>([]);
  protected readonly campaignRunId = signal<string>('');
  protected readonly campaignWindow = signal<CampaignWindow>('H72');
  protected readonly campaignMetric = signal<DashboardMetric>('TOTAL_INTERACTIONS');
  protected readonly campaignDimension = signal<CampaignDimension>('ROLE');
  protected readonly campaignReview = signal<CampaignReview | null>(null);
  protected readonly campaignComparison = signal<CampaignCohortComparison | null>(null);
  protected readonly campaignLoading = signal(false);
  protected readonly campaignCreating = signal(false);
  protected readonly campaignError = signal<string | null>(null);
  protected readonly campaignWindows: CampaignWindow[] = ['H24', 'H72', 'D7'];
  protected readonly campaignDimensions: CampaignDimension[] = ['ROLE', 'COORDINATION_POLICY'];
  protected readonly optimizationEligibility = signal<OptimizationEligibility | null>(null);
  protected readonly optimizationProposals = signal<OptimizationProposal[]>([]);
  protected readonly optimizationPersonas = signal<PersonaSummary[]>([]);
  protected readonly optimizationCandidateId = signal('');
  protected readonly optimizationBusy = signal(false);
  protected readonly optimizationMessage = signal<string | null>(null);
  protected readonly optimizationOrigin = signal<'ALL' | OptimizationProposalOrigin>('ALL');
  protected readonly robotChangeRobots = signal<RobotSummary[]>([]);
  protected readonly robotChangeProposals = signal<RobotChangeProposal[]>([]);
  protected readonly robotChangeTargets = signal<Record<string, string>>({});
  protected readonly robotChangeEligibilities = signal<Record<string, RobotChangeEligibility>>({});
  protected readonly robotChangeBusy = signal(false);
  protected readonly robotChangeMessage = signal<string | null>(null);
  protected readonly robotChangeGuardrails = signal<Record<string, AdaptiveGuardrailEvaluation>>({});
  private readonly subscriptions = new Subscription();
  private dashboardSubscription?: Subscription;
  private insightsSubscription?: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly publishing: PublishingService,
    private readonly analytics: PublicationAnalyticsService,
    private readonly personasService: PersonasService,
    private readonly robotsService: RobotsService,
  ) {}

  ngOnInit(): void {
    this.subscriptions.add(this.publishing.list().subscribe({
      next: (rows) => this.publications.set(rows.filter((row) => row.status === 'PUBLISHED').slice(0, 50)),
      error: () => this.error.set('Published items could not be loaded.'),
    }));
    this.subscriptions.add(this.personasService.list().subscribe({
      next: (rows) => this.optimizationPersonas.set(rows.filter((row) => row.status === 'ACTIVE')),
      error: () => this.optimizationPersonas.set([]),
    }));
    this.loadOptimizationProposals();
    this.subscriptions.add(this.robotsService.list().subscribe({
      next: (rows) => this.robotChangeRobots.set(rows.filter((row) => row.aiPolicy !== 'NO_AI')),
      error: () => this.robotChangeRobots.set([]),
    }));
    this.loadRobotChangeProposals();
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

      const tab = this.valid(['dashboard', 'insights', 'campaigns'] as const, params.get('tab'), 'dashboard') ?? 'dashboard';
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

      const campaignRunId = this.safeId(params.get('campaignRunId')) ?? '';
      const campaignWindow = this.valid(this.campaignWindows, params.get('campaignWindow'), 'H72') ?? 'H72';
      const campaignMetric = this.valid(this.trendMetrics, params.get('campaignMetric'), 'TOTAL_INTERACTIONS') ?? 'TOTAL_INTERACTIONS';
      const campaignDimension = this.valid(this.campaignDimensions, params.get('campaignDimension'), 'ROLE') ?? 'ROLE';
      this.campaignRunId.set(campaignRunId);
      this.campaignWindow.set(campaignWindow);
      this.campaignMetric.set(campaignMetric);
      this.campaignDimension.set(campaignDimension);
      this.loadCampaigns(campaignRunId);
      this.loadCampaignComparison(next, campaignWindow, campaignMetric, campaignDimension);
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

  protected setCampaignField(name: 'campaignRunId' | 'campaignWindow' | 'campaignMetric' | 'campaignDimension', value: string): void {
    this.router.navigate(['/analytics'], { queryParams: { [name]: value || null }, queryParamsHandling: 'merge' });
  }

  protected createCampaignReview(): void {
    const runId = this.campaignRunId();
    if (!runId || this.campaignCreating()) return;
    this.campaignCreating.set(true);
    this.campaignError.set(null);
    this.subscriptions.add(this.analytics.createCampaignReview(runId, this.campaignWindow(), this.campaignMetric()).subscribe({
      next: (review) => { this.campaignReview.set(review); this.loadOptimizationEligibility(review.id); this.campaignCreating.set(false); this.loadCampaigns(runId); },
      error: (error: HttpErrorResponse) => {
        this.campaignError.set(error.status === 409 ? 'This campaign is not ready for a performance review.' : 'Campaign review could not be created.');
        this.campaignCreating.set(false);
      },
    }));
  }

  private loadCampaigns(runId: string): void {
    this.campaignLoading.set(true);
    this.subscriptions.add(this.analytics.campaignOptions().subscribe({
      next: (options) => {
        this.campaignOptions.set(options);
        if (!runId) { this.campaignReview.set(null); this.optimizationEligibility.set(null); this.campaignLoading.set(false); return; }
        this.subscriptions.add(this.analytics.campaignReviews(runId).subscribe({
          next: (rows) => {
            const selected = rows.find((row) => row.observationWindow === this.campaignWindow() && row.primaryMetric === this.campaignMetric()) ?? null;
            if (!selected) { this.campaignReview.set(null); this.optimizationEligibility.set(null); this.campaignLoading.set(false); return; }
            this.subscriptions.add(this.analytics.campaignReview(selected.id).subscribe({
              next: (review) => { this.campaignReview.set(review); this.loadOptimizationEligibility(review.id); this.campaignLoading.set(false); },
              error: () => { this.campaignError.set('Campaign review could not be loaded.'); this.campaignLoading.set(false); },
            }));
          },
          error: () => { this.campaignError.set('Campaign review history could not be loaded.'); this.campaignLoading.set(false); },
        }));
      },
      error: () => { this.campaignError.set('Campaigns could not be loaded.'); this.campaignLoading.set(false); },
    }));
  }

  private loadCampaignComparison(filters: DashboardFilters, observationWindow: CampaignWindow,
      metric: DashboardMetric, dimension: CampaignDimension): void {
    if (!filters.dateFrom || !filters.dateTo) return;
    this.subscriptions.add(this.analytics.campaignComparison({
      dateFrom: filters.dateFrom, dateTo: filters.dateTo, observationWindow, metric, dimension, provider: filters.provider,
    }).subscribe({
      next: (comparison) => this.campaignComparison.set(comparison),
      error: () => this.campaignComparison.set(null),
    }));
  }

  protected campaignCoverage(): string {
    const review = this.campaignReview();
    if (!review) return '—';
    const sample = review.metrics[review.primaryMetric]?.sampleCount ?? 0;
    return `${sample} / ${review.eligibleByAgeCount}`;
  }

  protected chooseOptimizationCandidate(value: string): void { this.optimizationCandidateId.set(value); }
  protected chooseOptimizationOrigin(value: 'ALL' | OptimizationProposalOrigin): void {
    this.optimizationOrigin.set(value); this.loadOptimizationProposals();
  }

  protected createOptimizationProposal(): void {
    const review = this.campaignReview(); const candidate = this.optimizationCandidateId();
    if (!review || !candidate || this.optimizationBusy()) return;
    this.optimizationBusy.set(true); this.optimizationMessage.set(null);
    this.subscriptions.add(this.analytics.createOptimizationProposal(review.id, candidate).subscribe({
      next: (proposal) => { this.upsertProposal(proposal); this.optimizationBusy.set(false); this.optimizationMessage.set('Controlled test proposal created for human review.'); },
      error: (error: HttpErrorResponse) => { this.optimizationBusy.set(false); this.optimizationMessage.set(this.optimizationError(error)); },
    }));
  }

  protected optimizationAction(proposal: OptimizationProposal, action: 'approve' | 'reject' | 'materialize'): void {
    if (this.optimizationBusy()) return;
    this.optimizationBusy.set(true); this.optimizationMessage.set(null);
    const request = action === 'approve' ? this.analytics.approveOptimizationProposal(proposal.id)
      : action === 'reject' ? this.analytics.rejectOptimizationProposal(proposal.id)
        : this.analytics.materializeOptimizationProposal(proposal.id);
    this.subscriptions.add(request.subscribe({
      next: (updated) => { this.upsertProposal(updated); this.optimizationBusy.set(false);
        this.optimizationMessage.set(updated.status === 'STALE' ? 'The proposal is stale because a Persona changed or was archived.'
          : action === 'materialize' ? 'A DRAFT Experiment was created. No Robot was enrolled and nothing was activated.'
            : `Proposal ${action === 'approve' ? 'approved for testing' : 'rejected'}.`); },
      error: (error: HttpErrorResponse) => { this.optimizationBusy.set(false); this.optimizationMessage.set(this.optimizationError(error)); },
    }));
  }

  protected proposalCoverage(sample: number, eligible: number): string { return `${sample} / ${eligible}`; }

  private loadOptimizationEligibility(reviewId: string): void {
    this.subscriptions.add(this.analytics.optimizationEligibility(reviewId).subscribe({
      next: (value) => { this.optimizationEligibility.set(value); this.optimizationCandidateId.set(''); },
      error: () => this.optimizationEligibility.set(null),
    }));
  }

  private loadOptimizationProposals(): void {
    const origin=this.optimizationOrigin();
    this.subscriptions.add(this.analytics.optimizationProposals(50,origin==='ALL'?undefined:origin).subscribe({
      next: (rows) => this.optimizationProposals.set(rows), error: () => this.optimizationProposals.set([]),
    }));
  }

  private upsertProposal(proposal: OptimizationProposal): void {
    this.optimizationProposals.update((rows) => [proposal, ...rows.filter((row) => row.id !== proposal.id)]);
  }

  private optimizationError(error: HttpErrorResponse): string {
    const detail = typeof error.error?.detail === 'string' ? error.error.detail
      : typeof error.error?.message === 'string' ? error.error.message : '';
    if (detail.includes('INSUFFICIENT_SAMPLE')) return 'Both Persona cohorts need at least five comparable observations.';
    if (detail.includes('LOW_COVERAGE')) return 'Both Persona cohorts need at least 60% analytics coverage.';
    if (detail.includes('NO_MATERIAL')) return 'The observed median difference is below the 10% evidence gate.';
    if (detail.includes('STALE')) return 'The proposal is stale because a Persona changed or was archived.';
    return 'The controlled test proposal action could not be completed.';
  }

  protected robotsForExperiment(experimentId: string | null): RobotSummary[] {
    if (!experimentId) return [];
    return this.robotChangeRobots().filter((robot) => robot.experimentId === experimentId);
  }

  protected chooseRobotChangeTarget(optimizationProposalId: string, robotId: string): void {
    this.robotChangeTargets.update((map) => ({ ...map, [optimizationProposalId]: robotId }));
    this.robotChangeEligibilities.update((map) => { const next = { ...map }; delete next[optimizationProposalId]; return next; });
    if (!robotId) return;
    this.subscriptions.add(this.analytics.robotChangeEligibility(optimizationProposalId, robotId).subscribe({
      next: (result) => this.robotChangeEligibilities.update((map) => ({ ...map, [optimizationProposalId]: result })),
      error: () => this.robotChangeEligibilities.update((map) => {
        const next = { ...map };
        next[optimizationProposalId] = { eligible: false, reasonCode: 'ELIGIBILITY_CHECK_FAILED', sourceOptimizationProposalId: optimizationProposalId, targetRobotId: robotId };
        return next;
      }),
    }));
  }

  protected createRobotChangeProposal(optimizationProposalId: string): void {
    const robotId = this.robotChangeTargets()[optimizationProposalId];
    const eligibility = this.robotChangeEligibilities()[optimizationProposalId];
    if (!robotId || !eligibility?.eligible || this.robotChangeBusy()) return;
    this.robotChangeBusy.set(true); this.robotChangeMessage.set(null);
    this.subscriptions.add(this.analytics.createRobotChangeProposal(optimizationProposalId, robotId).subscribe({
      next: (proposal) => { this.upsertRobotChangeProposal(proposal); this.robotChangeBusy.set(false);
        this.robotChangeMessage.set('Robot change proposal created for human review. Approving and applying are separate, explicit actions.'); },
      error: (error: HttpErrorResponse) => { this.robotChangeBusy.set(false); this.robotChangeMessage.set(this.robotChangeError(error)); },
    }));
  }

  protected robotChangeAction(proposal: RobotChangeProposal, action: 'approve' | 'reject' | 'apply'): void {
    if (this.robotChangeBusy()) return;
    this.robotChangeBusy.set(true); this.robotChangeMessage.set(null);
    const request = action === 'approve' ? this.analytics.approveRobotChangeProposal(proposal.id)
      : action === 'reject' ? this.analytics.rejectRobotChangeProposal(proposal.id)
        : this.analytics.applyRobotChangeProposal(proposal.id);
    this.subscriptions.add(request.subscribe({
      next: (updated) => { this.upsertRobotChangeProposal(updated); this.robotChangeBusy.set(false);
        if(updated.status==='APPROVED')this.loadRobotChangeGuardrails(updated.id);
        const blocked=action==='apply'&&updated.status==='APPROVED';
        this.robotChangeMessage.set(updated.status === 'STALE' ? 'This proposal is stale: the Robot or the proposed Persona changed since the proposal was created. Applying has been blocked.'
          : blocked ? 'The proposal remains approved, but current adaptive guardrails block Apply. Review the evidence below.'
          : action === 'apply' ? 'Apply approved change: the Robot now uses the proposed Persona for future runs. Historical runs keep their original Persona.'
            : `Proposal ${action === 'approve' ? 'approved' : 'rejected'}.`); },
      error: (error: HttpErrorResponse) => { this.robotChangeBusy.set(false); this.robotChangeMessage.set(this.robotChangeError(error)); },
    }));
  }

  private loadRobotChangeProposals(): void {
    this.subscriptions.add(this.analytics.robotChangeProposals().subscribe({
      next: (rows) => { this.robotChangeProposals.set(rows); rows.filter(r=>r.status==='APPROVED').forEach(r=>this.loadRobotChangeGuardrails(r.id)); }, error: () => this.robotChangeProposals.set([]),
    }));
  }

  private upsertRobotChangeProposal(proposal: RobotChangeProposal): void {
    this.robotChangeProposals.update((rows) => [proposal, ...rows.filter((row) => row.id !== proposal.id)]);
  }

  private loadRobotChangeGuardrails(id:string):void {
    this.subscriptions.add(this.analytics.robotChangeGuardrails(id).subscribe({
      next:g=>this.robotChangeGuardrails.update(all=>({...all,[id]:g})),
      error:()=>undefined,
    }));
  }

  private robotChangeError(error: HttpErrorResponse): string {
    const detail = typeof error.error?.detail === 'string' ? error.error.detail
      : typeof error.error?.message === 'string' ? error.error.message : '';
    if (detail.includes('ROBOT_CONFIG_DIVERGED')) return 'The Robot configuration changed since this proposal was created; it is now stale.';
    if (detail.includes('CANDIDATE_PERSONA_ARCHIVED')) return 'The proposed Persona was archived; this proposal is now stale.';
    if (detail.includes('ROLLBACK_TARGET_NOT_CURRENT')) return 'A later configuration change already superseded this revision; it can no longer be rolled back.';
    if (detail.includes('PROPOSAL_NOT_APPROVED')) return 'This proposal must be approved before it can be applied.';
    if (detail.includes('PROPOSAL_NOT_READY_FOR_REVIEW')) return 'This proposal has already been reviewed.';
    return 'The Robot change proposal action could not be completed.';
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
