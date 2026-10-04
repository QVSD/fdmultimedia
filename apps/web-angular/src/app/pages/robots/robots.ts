import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EMPTY, Subscription, catchError, finalize, interval, startWith, switchMap } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { MediaAssetSummary } from '../../core/assets/asset.models';
import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import { SocialAccountSummary } from '../../core/social-accounts/social-account.models';
import { RobotsService } from '../../core/robots/robots.service';
import { RobotApprovalsService } from '../../core/robots/robot-approvals.service';
import {
  RobotAiPolicy,
  RobotAutonomyMode,
  RobotCadenceType,
  RobotRunSummary,
  RobotSelectionPolicy,
  RobotSourcePolicy,
  RobotSummary,
  RobotApprovalSummary,
  RobotHighlightStrategy,
  RobotCampaignPlanningPolicy,
  RobotCopyCoordinationPolicy,
} from '../../core/robots/robot.models';
import { ContentSourcesService } from '../../core/content-sources/content-sources.service';
import { ContentSourceSummary } from '../../core/content-sources/content-source.models';
import { AdaptiveMemoryTransition, MemoryOutcome, RobotAdaptiveMemory, SuppressionReason } from '../../core/publishing/adaptive-memory.models';
import { PostChangeSafetyEvaluation, RevisionSafety, RollbackRecommendation, SafetyWindow } from '../../core/publishing/post-change-safety.models';
import { PersonasService } from '../../core/personas/personas.service';
import { PersonaSummary } from '../../core/personas/persona.models';
import { SuggestionLanguage, SuggestionTone } from '../../core/content-suggestions/content-suggestion.models';
import { ExperimentsService } from '../../core/experiments/experiments.service';
import { ExperimentSummary } from '../../core/experiments/experiment.models';
import { CampaignPlansService } from '../../core/campaign-plans/campaign-plans.service';
import {
  CampaignContentPlanItemSummary,
  CampaignContentPlanSummary,
  CampaignPlanRole,
  CampaignPlanStatus,
} from '../../core/campaign-plans/campaign-plan.models';
import { CampaignCopyService } from '../../core/campaign-copy/campaign-copy.service';
import {
  CampaignCopyItemSummary,
  CampaignCopySetStatus,
  CampaignCopySetSummary,
} from '../../core/campaign-copy/campaign-copy.models';
import { AutonomousProposalEligibility, RobotAdaptivePolicy, RobotAdaptivePolicyRevision, RobotConfigurationRevision } from '../../core/publishing/robot-change-proposal.models';

type LoadState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-robots',
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './robots.html',
  styleUrl: './robots.scss',
})
export class Robots implements OnInit, OnDestroy {
  protected readonly activeView = signal<'robots' | 'approvals'>('robots');

  protected readonly robots = signal<RobotSummary[]>([]);
  protected readonly robotsLoadState = signal<LoadState>('loading');
  protected readonly runs = signal<RobotRunSummary[]>([]);
  protected readonly approvals = signal<RobotApprovalSummary[]>([]);
  protected readonly approvalsLoadState = signal<LoadState>('loading');

  protected readonly eligibleAssets = signal<MediaAssetSummary[]>([]);
  protected readonly socialAccounts = signal<SocialAccountSummary[]>([]);
  protected readonly contentSources = signal<ContentSourceSummary[]>([]);
  protected readonly personas = signal<PersonaSummary[]>([]);
  protected readonly experiments = signal<ExperimentSummary[]>([]);

  protected readonly expandedRobots = signal<Record<string, boolean>>({});
  protected readonly runNowBusy = signal<Record<string, boolean>>({});
  protected readonly runNowErrors = signal<Record<string, string | null>>({});
  protected readonly pauseResumeBusy = signal<Record<string, boolean>>({});

  protected readonly showCreateForm = signal(false);
  protected readonly createName = signal('');
  protected readonly createDescription = signal('');
  protected readonly createAutonomyMode = signal<RobotAutonomyMode>('DRAFT_ONLY');
  protected readonly createSourcePolicy = signal<RobotSourcePolicy>('EXISTING_ASSET');
  protected readonly createSourceAssetId = signal('');
  protected readonly createContentSourceId = signal('');
  protected readonly createSelectionPolicy = signal<RobotSelectionPolicy>('OLDEST_UNPROCESSED');
  protected readonly createTargetAccountId = signal('');
  protected readonly createCadenceType = signal<RobotCadenceType>('MANUAL_ONLY');
  protected readonly createCadenceIntervalHours = signal(24);
  protected readonly createScheduleDelayMinutes = signal(60);
  protected readonly createMaxRunsPerDay = signal(1);
  protected readonly createAiPolicy = signal<RobotAiPolicy>('NO_AI');
  protected readonly createPersonaId = signal('');
  protected readonly createAiLanguageOverride = signal<SuggestionLanguage | ''>('');
  protected readonly createAiToneOverride = signal<SuggestionTone | ''>('');
  protected readonly createExperimentId = signal('');
  protected readonly createHighlightStrategy = signal<RobotHighlightStrategy>('TOP_HIGHLIGHT');
  protected readonly createHighlightCount = signal(3);
  protected readonly createOutputSpacingMinutes = signal(60);
  protected readonly createCampaignPlanningPolicy = signal<RobotCampaignPlanningPolicy>('NO_CAMPAIGN_PLAN');
  protected readonly createCopyCoordinationPolicy = signal<RobotCopyCoordinationPolicy>('INDEPENDENT_COPY');
  protected readonly createBusy = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly approveBusy = signal<Record<string, boolean>>({});
  protected readonly approveErrors = signal<Record<string, string | null>>({});
  protected readonly rejectBusy = signal<Record<string, boolean>>({});

  // ---- Phase 17E: campaign content planning ----
  protected readonly campaignPlansByRun = signal<Record<string, CampaignContentPlanSummary[]>>({});
  protected readonly campaignPlanBusy = signal<Record<string, boolean>>({});
  protected readonly campaignReviewBusy = signal<Record<string, boolean>>({});
  protected readonly campaignReviewErrors = signal<Record<string, string | null>>({});

  // ---- Phase 17F: coordinated campaign copy ----
  protected readonly campaignCopySetsByRun = signal<Record<string, CampaignCopySetSummary[]>>({});
  protected readonly copySetBusy = signal<Record<string, boolean>>({});
  protected readonly copySetReviewBusy = signal<Record<string, boolean>>({});
  protected readonly copySetReviewErrors = signal<Record<string, string | null>>({});

  // ---- Phase 17I: Robot configuration history & rollback ----
  protected readonly configurationHistoryByRobot = signal<Record<string, RobotConfigurationRevision[]>>({});
  protected readonly configurationHistoryExpanded = signal<Record<string, boolean>>({});
  protected readonly rollbackConfirming = signal<Record<string, boolean>>({});
  protected readonly safetyByRobot = signal<Record<string, RevisionSafety[]>>({});
  protected readonly recommendationReviewing = signal<Record<string, boolean>>({});
  protected readonly recommendationBusy = signal<Record<string, boolean>>({});
  protected readonly recommendationErrors = signal<Record<string, string | null>>({});
  protected readonly adaptiveMemoryByRobot = signal<Record<string, RobotAdaptiveMemory>>({});
  protected readonly rollbackBusy = signal<Record<string, boolean>>({});
  protected readonly rollbackErrors = signal<Record<string, string | null>>({});
  protected readonly adaptivePolicyExpanded = signal<Record<string, boolean>>({});
  protected readonly adaptivePolicies = signal<Record<string, RobotAdaptivePolicy>>({});
  protected readonly adaptivePolicyHistory = signal<Record<string, RobotAdaptivePolicyRevision[]>>({});
  protected readonly adaptivePolicyBusy = signal<Record<string, boolean>>({});
  protected readonly adaptivePolicyErrors = signal<Record<string, string | null>>({});
  protected readonly adaptiveProposalEligibility = signal<Record<string, AutonomousProposalEligibility>>({});
  protected readonly adaptiveProposalEligibilityBusy = signal<Record<string, boolean>>({});

  private robotsSubscription?: Subscription;
  private approvalsSubscription?: Subscription;
  private runsSubscription?: Subscription;

  constructor(
    private readonly robotsService: RobotsService,
    private readonly approvalsService: RobotApprovalsService,
    private readonly assetsService: AssetsService,
    private readonly socialAccountsService: SocialAccountsService,
    private readonly contentSourcesService: ContentSourcesService,
    private readonly personasService: PersonasService,
    private readonly experimentsService: ExperimentsService,
    private readonly campaignPlansService: CampaignPlansService,
    private readonly campaignCopyService: CampaignCopyService,
  ) {}

  ngOnInit(): void {
    this.assetsService
      .list()
      .pipe(catchError(() => EMPTY))
      .subscribe((assets) => this.eligibleAssets.set(assets.filter((asset) => this.isEligibleSource(asset))));

    this.socialAccountsService
      .list()
      .pipe(catchError(() => EMPTY))
      .subscribe((accounts) => this.socialAccounts.set(accounts));

    this.contentSourcesService
      .list()
      .pipe(catchError(() => EMPTY))
      .subscribe((sources) => this.contentSources.set(sources));

    this.personasService
      .list()
      .pipe(catchError(() => EMPTY))
      .subscribe((personas) => this.personas.set(personas));

    this.experimentsService
      .list()
      .pipe(catchError(() => EMPTY))
      .subscribe((experiments) => this.experiments.set(experiments));

    this.robotsSubscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.robotsService.list().pipe(
            catchError(() => {
              this.robotsLoadState.set('error');
              return EMPTY;
            }),
          ),
        ),
      )
      .subscribe((robots) => {
        this.robots.set(robots);
        this.robotsLoadState.set('ready');
      });

    this.approvalsSubscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.approvalsService.list().pipe(
            catchError(() => {
              this.approvalsLoadState.set('error');
              return EMPTY;
            }),
          ),
        ),
      )
      .subscribe((approvals) => {
        this.approvals.set(approvals);
        this.approvalsLoadState.set('ready');
      });

    this.runsSubscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() => this.robotsService.allRuns().pipe(catchError(() => EMPTY))),
      )
      .subscribe((runs) => this.runs.set(runs));
  }

  ngOnDestroy(): void {
    this.robotsSubscription?.unsubscribe();
    this.approvalsSubscription?.unsubscribe();
    this.runsSubscription?.unsubscribe();
  }

  protected switchView(view: 'robots' | 'approvals'): void {
    this.activeView.set(view);
  }

  private isEligibleSource(asset: MediaAssetSummary): boolean {
    return asset.status === 'READY' && asset.inspectionStatus === 'INSPECTED' && asset.hasVideo === true;
  }

  protected pendingApprovalCount(): number {
    return this.approvals().filter((approval) => approval.status === 'PENDING').length;
  }

  // ---- create form ----

  protected toggleCreateForm(): void {
    this.showCreateForm.update((value) => !value);
    this.createError.set(null);
  }

  protected autonomyModeExplanation(mode: RobotAutonomyMode): string {
    switch (mode) {
      case 'DRAFT_ONLY':
        return 'Creates prepared drafts. You decide when and where to publish.';
      case 'REVIEW_REQUIRED':
        return 'Prepares content and waits for your approval before scheduling.';
      case 'AUTO_SCHEDULE':
        return 'Automatically schedules prepared content according to this Robot’s rules. Phase 11C only allows this for the TEST provider.';
    }
  }

  protected requiresAccount(mode: RobotAutonomyMode): boolean {
    return mode !== 'DRAFT_ONLY';
  }

  protected requiresDelay(mode: RobotAutonomyMode): boolean {
    return mode !== 'DRAFT_ONLY';
  }

  protected autoScheduleAccounts(): SocialAccountSummary[] {
    return this.socialAccounts().filter((account) => account.status === 'ACTIVE' && account.platform === 'TEST');
  }

  protected reviewOrDraftAccounts(): SocialAccountSummary[] {
    return this.socialAccounts().filter((account) => account.status === 'ACTIVE');
  }

  protected accountOptionsFor(mode: RobotAutonomyMode): SocialAccountSummary[] {
    return mode === 'AUTO_SCHEDULE' ? this.autoScheduleAccounts() : this.reviewOrDraftAccounts();
  }

  protected sourcePolicyLabel(policy: RobotSourcePolicy): string {
    return policy === 'EXISTING_ASSET' ? 'Fixed asset' : 'Content source';
  }

  protected selectionPolicyLabel(policy: RobotSelectionPolicy | null): string {
    switch (policy) {
      case 'OLDEST_UNPROCESSED':
        return 'Oldest unprocessed';
      case 'NEWEST_UNPROCESSED':
        return 'Newest unprocessed';
      default:
        return '';
    }
  }

  protected activeContentSources(): ContentSourceSummary[] {
    return this.contentSources().filter((source) => source.status === 'ACTIVE');
  }

  // ---- Phase 12C: AI enrichment policy ----

  protected activePersonas(): PersonaSummary[] {
    return this.personas().filter((persona) => persona.status === 'ACTIVE');
  }

  protected aiPolicyExplanation(policy: RobotAiPolicy): string {
    switch (policy) {
      case 'NO_AI':
        return 'Prepare the Draft without AI copy.';
      case 'GENERATE_FOR_REVIEW':
        return 'Generate AI copy and wait for you to review/apply it.';
      case 'GENERATE_AND_APPLY':
        return 'Generate and apply AI copy automatically before the Robot continues.';
    }
  }

  protected requiresAiConfig(policy: RobotAiPolicy): boolean {
    return policy !== 'NO_AI';
  }

  // ---- Phase 17E: campaign content planning ----

  /** Coordinates messaging across a series without changing which highlights were selected. */
  protected campaignPlanningPolicyExplanation(policy: RobotCampaignPlanningPolicy): string {
    switch (policy) {
      case 'NO_CAMPAIGN_PLAN':
        return 'Each output is generated independently, with no cross-output coordination.';
      case 'DETERMINISTIC_PLAN':
        return 'Assign a role (intro/deep dive/conclusion) and simple guidance to each output, no AI involved.';
      case 'AI_PLAN_FOR_REVIEW':
        return 'An AI proposes a campaign title, roles, and guidance for your review before it is used.';
      case 'AI_PLAN_AND_APPLY':
        return 'An AI proposes a campaign plan and it is applied automatically before outputs continue.';
    }
  }

  protected campaignPlanningPolicyLabel(policy: RobotCampaignPlanningPolicy): string {
    switch (policy) {
      case 'NO_CAMPAIGN_PLAN': return 'No campaign plan';
      case 'DETERMINISTIC_PLAN': return 'Deterministic campaign plan';
      case 'AI_PLAN_FOR_REVIEW': return 'AI campaign plan (review)';
      case 'AI_PLAN_AND_APPLY': return 'AI campaign plan (auto-apply)';
    }
  }

  protected campaignStatusLabel(status: CampaignPlanStatus): string {
    switch (status) {
      case 'GENERATING': return 'Generating...';
      case 'READY_FOR_REVIEW': return 'Ready for review';
      case 'APPLIED': return 'Applied';
      case 'REJECTED': return 'Rejected';
      case 'FAILED': return 'Failed';
    }
  }

  protected campaignRoleLabel(role: CampaignPlanRole): string {
    switch (role) {
      case 'INTRODUCTION': return 'Introduction';
      case 'DEEP_DIVE': return 'Deep dive';
      case 'SUPPORTING_POINT': return 'Supporting point';
      case 'CONCLUSION': return 'Conclusion';
      case 'STANDALONE': return 'Standalone';
    }
  }

  /** The one revision considered effective for this run right now — never a superseded one (item 27/47). */
  protected currentCampaignPlan(run: RobotRunSummary): CampaignContentPlanSummary | null {
    return (this.campaignPlansByRun()[run.id] ?? []).find((plan) => plan.current) ?? null;
  }

  protected historicalCampaignPlans(run: RobotRunSummary): CampaignContentPlanSummary[] {
    return (this.campaignPlansByRun()[run.id] ?? []).filter((plan) => !plan.current);
  }

  protected campaignItemForOutput(plan: CampaignContentPlanSummary, outputId: string): CampaignContentPlanItemSummary | null {
    return plan.items.find((item) => item.robotRunOutputId === outputId) ?? null;
  }

  protected loadCampaignPlansForRun(runId: string): void {
    this.campaignPlanBusy.update((busy) => ({ ...busy, [runId]: true }));
    this.campaignPlansService
      .listForRun(runId)
      .pipe(finalize(() => this.campaignPlanBusy.update((busy) => ({ ...busy, [runId]: false }))))
      .subscribe({
        next: (plans) => this.campaignPlansByRun.update((byRun) => ({ ...byRun, [runId]: plans })),
        error: () => {},
      });
  }

  protected applyCampaignPlan(plan: CampaignContentPlanSummary): void {
    this.campaignReviewErrors.update((errors) => ({ ...errors, [plan.id]: null }));
    this.campaignReviewBusy.update((busy) => ({ ...busy, [plan.id]: true }));
    this.campaignPlansService
      .apply(plan.id)
      .pipe(finalize(() => this.campaignReviewBusy.update((busy) => ({ ...busy, [plan.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceCampaignPlan(plan.robotRunId, updated),
        error: () => this.campaignReviewErrors.update((errors) => ({ ...errors, [plan.id]: 'Could not apply the campaign plan.' })),
      });
  }

  protected rejectCampaignPlan(plan: CampaignContentPlanSummary): void {
    this.campaignReviewErrors.update((errors) => ({ ...errors, [plan.id]: null }));
    this.campaignReviewBusy.update((busy) => ({ ...busy, [plan.id]: true }));
    this.campaignPlansService
      .reject(plan.id)
      .pipe(finalize(() => this.campaignReviewBusy.update((busy) => ({ ...busy, [plan.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceCampaignPlan(plan.robotRunId, updated),
        error: () => this.campaignReviewErrors.update((errors) => ({ ...errors, [plan.id]: 'Could not reject the campaign plan.' })),
      });
  }

  protected regenerateCampaignPlan(run: RobotRunSummary): void {
    this.campaignReviewErrors.update((errors) => ({ ...errors, [run.id]: null }));
    this.campaignReviewBusy.update((busy) => ({ ...busy, [run.id]: true }));
    this.campaignPlansService
      .regenerate(run.id)
      .pipe(finalize(() => this.campaignReviewBusy.update((busy) => ({ ...busy, [run.id]: false }))))
      .subscribe({
        next: () => this.loadCampaignPlansForRun(run.id),
        error: () => this.campaignReviewErrors.update((errors) => ({ ...errors, [run.id]: 'Could not regenerate the campaign plan.' })),
      });
  }

  private replaceCampaignPlan(runId: string, updated: CampaignContentPlanSummary): void {
    this.campaignPlansByRun.update((byRun) => ({
      ...byRun,
      [runId]: (byRun[runId] ?? []).map((plan) => (plan.id === updated.id ? updated : plan)),
    }));
  }

  // ---- Phase 17F: coordinated campaign copy ----

  /** Mirrors the backend's own cross-field validation (RobotService.validateCopyCoordinationPolicy): coordinated copy is only ever satisfiable with an active campaign plan and an active AI policy. */
  protected copyCoordinationEligible(): boolean {
    return this.createHighlightStrategy() === 'TOP_DIVERSE_HIGHLIGHTS'
      && this.createCampaignPlanningPolicy() !== 'NO_CAMPAIGN_PLAN'
      && this.createAiPolicy() !== 'NO_AI';
  }

  /** Coordinates final copy across campaign outputs while keeping each post independently reviewable. */
  protected copyCoordinationPolicyExplanation(policy: RobotCopyCoordinationPolicy): string {
    switch (policy) {
      case 'INDEPENDENT_COPY':
        return 'Each output’s social copy is generated independently, exactly as without campaign copy coordination.';
      case 'COORDINATED_COPY_FOR_REVIEW':
        return 'An AI coordinates hook/caption/hashtags across all outputs in the series for your review before it is used.';
      case 'COORDINATED_COPY_AND_APPLY':
        return 'An AI coordinates copy across the series and applies it automatically before outputs continue.';
    }
  }

  protected copyCoordinationPolicyLabel(policy: RobotCopyCoordinationPolicy): string {
    switch (policy) {
      case 'INDEPENDENT_COPY': return 'Independent copy';
      case 'COORDINATED_COPY_FOR_REVIEW': return 'Coordinated copy (review)';
      case 'COORDINATED_COPY_AND_APPLY': return 'Coordinated copy (auto-apply)';
    }
  }

  protected copySetStatusLabel(status: CampaignCopySetStatus): string {
    switch (status) {
      case 'GENERATING': return 'Generating...';
      case 'READY_FOR_REVIEW': return 'Ready for review';
      case 'APPLIED': return 'Applied';
      case 'REJECTED': return 'Rejected';
      case 'FAILED': return 'Failed';
    }
  }

  /** The one revision considered effective for this run right now — never a superseded one. */
  protected currentCopySet(run: RobotRunSummary): CampaignCopySetSummary | null {
    return (this.campaignCopySetsByRun()[run.id] ?? []).find((copySet) => copySet.current) ?? null;
  }

  protected historicalCopySets(run: RobotRunSummary): CampaignCopySetSummary[] {
    return (this.campaignCopySetsByRun()[run.id] ?? []).filter((copySet) => !copySet.current);
  }

  protected copyItemForOutput(copySet: CampaignCopySetSummary, outputId: string): CampaignCopyItemSummary | null {
    return copySet.items.find((item) => item.robotRunOutputId === outputId) ?? null;
  }

  protected loadCopySetsForRun(runId: string): void {
    this.copySetBusy.update((busy) => ({ ...busy, [runId]: true }));
    this.campaignCopyService
      .listForRun(runId)
      .pipe(finalize(() => this.copySetBusy.update((busy) => ({ ...busy, [runId]: false }))))
      .subscribe({
        next: (copySets) => this.campaignCopySetsByRun.update((byRun) => ({ ...byRun, [runId]: copySets })),
        error: () => {},
      });
  }

  protected applyCopySet(copySet: CampaignCopySetSummary): void {
    this.copySetReviewErrors.update((errors) => ({ ...errors, [copySet.id]: null }));
    this.copySetReviewBusy.update((busy) => ({ ...busy, [copySet.id]: true }));
    this.campaignCopyService
      .apply(copySet.id)
      .pipe(finalize(() => this.copySetReviewBusy.update((busy) => ({ ...busy, [copySet.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceCopySet(copySet.robotRunId, updated),
        error: () => this.copySetReviewErrors.update((errors) => ({ ...errors, [copySet.id]: 'Could not apply the coordinated copy.' })),
      });
  }

  protected rejectCopySet(copySet: CampaignCopySetSummary): void {
    this.copySetReviewErrors.update((errors) => ({ ...errors, [copySet.id]: null }));
    this.copySetReviewBusy.update((busy) => ({ ...busy, [copySet.id]: true }));
    this.campaignCopyService
      .reject(copySet.id)
      .pipe(finalize(() => this.copySetReviewBusy.update((busy) => ({ ...busy, [copySet.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceCopySet(copySet.robotRunId, updated),
        error: () => this.copySetReviewErrors.update((errors) => ({ ...errors, [copySet.id]: 'Could not reject the coordinated copy.' })),
      });
  }

  protected regenerateCopySet(run: RobotRunSummary): void {
    this.copySetReviewErrors.update((errors) => ({ ...errors, [run.id]: null }));
    this.copySetReviewBusy.update((busy) => ({ ...busy, [run.id]: true }));
    this.campaignCopyService
      .regenerate(run.id)
      .pipe(finalize(() => this.copySetReviewBusy.update((busy) => ({ ...busy, [run.id]: false }))))
      .subscribe({
        next: () => this.loadCopySetsForRun(run.id),
        error: () => this.copySetReviewErrors.update((errors) => ({ ...errors, [run.id]: 'Could not regenerate the coordinated copy.' })),
      });
  }

  private replaceCopySet(runId: string, updated: CampaignCopySetSummary): void {
    this.campaignCopySetsByRun.update((byRun) => ({
      ...byRun,
      [runId]: (byRun[runId] ?? []).map((copySet) => (copySet.id === updated.id ? updated : copySet)),
    }));
  }

  // ---- Phase 14A: controlled experiments ----

  /** Item 55: a Robot may reference a DRAFT/ACTIVE/PAUSED Experiment (flexible setup order) but never a terminal one. */
  protected attachableExperiments(): ExperimentSummary[] {
    return this.experiments().filter((experiment) => experiment.status !== 'COMPLETED' && experiment.status !== 'CANCELLED');
  }

  protected experimentNameFor(experimentId: string | null): string | null {
    if (!experimentId) {
      return null;
    }
    return this.experiments().find((experiment) => experiment.id === experimentId)?.name ?? experimentId.slice(0, 8);
  }

  protected createRobot(): void {
    this.createError.set(null);
    const name = this.createName().trim();
    if (!name) {
      this.createError.set('Name is required.');
      return;
    }
    const sourcePolicy = this.createSourcePolicy();
    if (sourcePolicy === 'EXISTING_ASSET' && !this.createSourceAssetId()) {
      this.createError.set('Choose a source asset.');
      return;
    }
    if (sourcePolicy === 'CONTENT_SOURCE' && !this.createContentSourceId()) {
      this.createError.set('Choose a content source.');
      return;
    }
    const mode = this.createAutonomyMode();
    if (this.requiresAccount(mode) && !this.createTargetAccountId()) {
      this.createError.set('Choose a target account for this autonomy mode.');
      return;
    }
    const aiPolicy = this.createAiPolicy();
    if (this.createHighlightStrategy() === 'TOP_DIVERSE_HIGHLIGHTS'
        && (this.createHighlightCount() < 1 || this.createHighlightCount() > 5)) {
      this.createError.set('Highlight count must be between 1 and 5.');
      return;
    }
    this.createBusy.set(true);
    this.robotsService
      .create({
        name,
        description: this.createDescription().trim() || null,
        autonomyMode: mode,
        sourcePolicy,
        sourceAssetId: sourcePolicy === 'EXISTING_ASSET' ? this.createSourceAssetId() : null,
        contentSourceId: sourcePolicy === 'CONTENT_SOURCE' ? this.createContentSourceId() : null,
        selectionPolicy: sourcePolicy === 'CONTENT_SOURCE' ? this.createSelectionPolicy() : null,
        targetSocialAccountId: this.requiresAccount(mode) ? this.createTargetAccountId() : null,
        cadenceType: this.createCadenceType(),
        cadenceIntervalHours: this.createCadenceType() === 'INTERVAL' ? this.createCadenceIntervalHours() : null,
        scheduleDelayMinutes: this.requiresDelay(mode) ? this.createScheduleDelayMinutes() : null,
        maxRunsPerDay: this.createMaxRunsPerDay(),
        aiPolicy,
        personaId: this.requiresAiConfig(aiPolicy) && this.createPersonaId() ? this.createPersonaId() : null,
        aiLanguageOverride: this.requiresAiConfig(aiPolicy) && this.createAiLanguageOverride() ? this.createAiLanguageOverride() as SuggestionLanguage : null,
        aiToneOverride: this.requiresAiConfig(aiPolicy) && this.createAiToneOverride() ? this.createAiToneOverride() as SuggestionTone : null,
        experimentId: this.requiresAiConfig(aiPolicy) && this.createExperimentId() ? this.createExperimentId() : null,
        highlightStrategy: this.createHighlightStrategy(),
        highlightCount: this.createHighlightStrategy() === 'TOP_DIVERSE_HIGHLIGHTS' ? this.createHighlightCount() : 1,
        outputSpacingMinutes: this.createOutputSpacingMinutes(),
        campaignPlanningPolicy: this.createHighlightStrategy() === 'TOP_DIVERSE_HIGHLIGHTS'
          ? this.createCampaignPlanningPolicy() : 'NO_CAMPAIGN_PLAN',
        copyCoordinationPolicy: this.copyCoordinationEligible() ? this.createCopyCoordinationPolicy() : 'INDEPENDENT_COPY',
      })
      .pipe(finalize(() => this.createBusy.set(false)))
      .subscribe({
        next: (robot) => {
          this.robots.set([robot, ...this.robots()]);
          this.showCreateForm.set(false);
          this.createName.set('');
          this.createDescription.set('');
          this.createExperimentId.set('');
        },
        error: () => this.createError.set('Robot could not be created.'),
      });
  }

  // ---- robot list actions ----

  protected isExpanded(robot: RobotSummary): boolean {
    return this.expandedRobots()[robot.id] ?? false;
  }

  protected toggleExpanded(robot: RobotSummary): void {
    const expanding = !(this.expandedRobots()[robot.id] ?? false);
    this.expandedRobots.update((items) => ({ ...items, [robot.id]: expanding }));
    if (expanding) {
      for (const run of this.runsForRobot(robot)) {
        if (run.campaignPlanId && !this.campaignPlansByRun()[run.id]) {
          this.loadCampaignPlansForRun(run.id);
        }
        if (run.campaignCopySetId && !this.campaignCopySetsByRun()[run.id]) {
          this.loadCopySetsForRun(run.id);
        }
      }
    }
  }

  protected runsForRobot(robot: RobotSummary): RobotRunSummary[] {
    return this.runs()
      .filter((run) => run.robotId === robot.id)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  protected latestRunStatusFor(robot: RobotSummary): string {
    const latest = this.runsForRobot(robot)[0];
    return latest ? this.runStatusLabel(latest.status) : 'No runs yet';
  }

  /** Answers "why did this Robot choose this video?" for the run history view — never free-text reasoning, just the recorded policy/source/asset. */
  protected runSourceLabel(run: RobotRunSummary): string {
    if (run.contentSourceName) {
      const policy = this.selectionPolicyLabel(run.selectionPolicy);
      const asset = run.sourceAssetId ? run.sourceAssetId.slice(0, 8) : null;
      return asset
        ? `Selected by ${policy} from "${run.contentSourceName}" (${asset})`
        : `${policy} from "${run.contentSourceName}": nothing eligible`;
    }
    return run.sourceAssetId ? `Fixed asset ${run.sourceAssetId.slice(0, 8)}` : '';
  }

  protected runStatusLabel(status: RobotRunSummary['status']): string {
    switch (status) {
      case 'RUNNING':
        return 'Preparing media';
      case 'WAITING_FOR_DRAFT':
        return 'Preparing draft';
      case 'WAITING_FOR_AI':
        return 'Generating AI';
      case 'WAITING_FOR_AI_REVIEW':
        return 'Waiting for AI review';
      case 'WAITING_FOR_REVIEW':
        return 'Waiting for publishing approval';
      case 'SUCCEEDED':
        return 'Succeeded';
      case 'PARTIALLY_SUCCEEDED':
        return 'Partially succeeded';
      case 'FAILED':
        return 'Failed';
      case 'CANCELLED':
        return 'Cancelled';
    }
  }

  protected robotSourceLabel(robot: RobotSummary): string {
    if (robot.sourcePolicy === 'CONTENT_SOURCE') {
      return `${robot.contentSourceName ?? robot.contentSourceId?.slice(0, 8) ?? 'Content source'} · ${this.selectionPolicyLabel(robot.selectionPolicy)}`;
    }
    return robot.sourceAssetFilename || (robot.sourceAssetId ? robot.sourceAssetId.slice(0, 8) : 'Fixed asset');
  }

  protected robotStatusLabel(status: RobotSummary['status']): string {
    switch (status) {
      case 'ACTIVE':
        return 'Active';
      case 'PAUSED':
        return 'Paused';
      case 'DISABLED':
        return 'Disabled';
    }
  }

  protected canRunNow(robot: RobotSummary): boolean {
    if (robot.status !== 'ACTIVE') {
      return false;
    }
    return !this.runsForRobot(robot).some((run) => !this.isRunTerminal(run.status));
  }

  private isRunTerminal(status: RobotRunSummary['status']): boolean {
    return status === 'SUCCEEDED' || status === 'PARTIALLY_SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED';
  }

  protected outputDuration(startMs: number, endMs: number): string {
    return `${((endMs - startMs) / 1000).toFixed(1)}s`;
  }

  protected runNow(robot: RobotSummary): void {
    this.runNowErrors.update((errors) => ({ ...errors, [robot.id]: null }));
    this.runNowBusy.update((busy) => ({ ...busy, [robot.id]: true }));
    this.robotsService
      .runNow(robot.id)
      .pipe(finalize(() => this.runNowBusy.update((busy) => ({ ...busy, [robot.id]: false }))))
      .subscribe({
        next: (run) => this.runs.set([run, ...this.runs().filter((existing) => existing.id !== run.id)]),
        error: () => this.runNowErrors.update((errors) => ({ ...errors, [robot.id]: 'Run could not be started.' })),
      });
  }

  protected togglePause(robot: RobotSummary): void {
    this.pauseResumeBusy.update((busy) => ({ ...busy, [robot.id]: true }));
    const action = robot.status === 'ACTIVE' ? this.robotsService.pause(robot.id) : this.robotsService.resume(robot.id);
    action.pipe(finalize(() => this.pauseResumeBusy.update((busy) => ({ ...busy, [robot.id]: false })))).subscribe({
      next: (updated) => this.robots.set(this.robots().map((existing) => (existing.id === updated.id ? updated : existing))),
      error: () => undefined,
    });
  }

  // ---- approvals ----

  protected approvalsForList(): RobotApprovalSummary[] {
    return this.approvals().slice().sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  protected approvalStatusLabel(status: RobotApprovalSummary['status']): string {
    switch (status) {
      case 'PENDING':
        return 'Pending';
      case 'APPROVED':
        return 'Approved';
      case 'REJECTED':
        return 'Rejected';
      case 'CANCELLED':
        return 'Cancelled';
    }
  }

  protected approve(approval: RobotApprovalSummary): void {
    this.approveErrors.update((errors) => ({ ...errors, [approval.id]: null }));
    this.approveBusy.update((busy) => ({ ...busy, [approval.id]: true }));
    this.approvalsService
      .approve(approval.id, null)
      .pipe(finalize(() => this.approveBusy.update((busy) => ({ ...busy, [approval.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceApproval(updated),
        error: () => this.approveErrors.update((errors) => ({ ...errors, [approval.id]: 'Could not approve.' })),
      });
  }

  protected reject(approval: RobotApprovalSummary): void {
    this.rejectBusy.update((busy) => ({ ...busy, [approval.id]: true }));
    this.approvalsService
      .reject(approval.id)
      .pipe(finalize(() => this.rejectBusy.update((busy) => ({ ...busy, [approval.id]: false }))))
      .subscribe({
        next: (updated) => this.replaceApproval(updated),
        error: () => undefined,
      });
  }

  private replaceApproval(approval: RobotApprovalSummary): void {
    this.approvals.set(this.approvals().map((existing) => (existing.id === approval.id ? approval : existing)));
  }

  // ---- Phase 17I: Robot configuration history & rollback ----

  protected isConfigurationHistoryExpanded(robot: RobotSummary): boolean {
    return this.configurationHistoryExpanded()[robot.id] ?? false;
  }

  protected toggleConfigurationHistory(robot: RobotSummary): void {
    const expanding = !this.isConfigurationHistoryExpanded(robot);
    this.configurationHistoryExpanded.update((items) => ({ ...items, [robot.id]: expanding }));
    if (expanding && !this.configurationHistoryByRobot()[robot.id]) {
      this.loadConfigurationHistory(robot.id);
    }
  }

  protected configurationHistoryFor(robot: RobotSummary): RobotConfigurationRevision[] {
    return this.configurationHistoryByRobot()[robot.id] ?? [];
  }

  /** The current (highest-numbered) revision is the only one eligible for rollback — no time travel. */
  protected isCurrentRevision(robot: RobotSummary, revision: RobotConfigurationRevision): boolean {
    const history = this.configurationHistoryFor(robot);
    return history.length > 0 && history[0].id === revision.id;
  }

  protected loadConfigurationHistory(robotId: string): void {
    this.robotsService.configurationRevisions(robotId).subscribe({
      next: (revisions) => this.configurationHistoryByRobot.update((byRobot) => ({ ...byRobot, [robotId]: revisions })),
      error: () => this.configurationHistoryByRobot.update((byRobot) => ({ ...byRobot, [robotId]: [] })),
    });
    this.loadSafety(robotId);
  }

  protected confirmRollback(revision: RobotConfigurationRevision): void {
    this.rollbackConfirming.update((items) => ({ ...items, [revision.id]: true }));
  }

  protected cancelRollback(revision: RobotConfigurationRevision): void {
    this.rollbackConfirming.update((items) => ({ ...items, [revision.id]: false }));
  }

  protected rollback(robot: RobotSummary, revision: RobotConfigurationRevision): void {
    this.rollbackErrors.update((errors) => ({ ...errors, [revision.id]: null }));
    this.rollbackBusy.update((busy) => ({ ...busy, [revision.id]: true }));
    this.robotsService
      .rollbackConfigurationRevision(robot.id, revision.id, null)
      .pipe(finalize(() => this.rollbackBusy.update((busy) => ({ ...busy, [revision.id]: false }))))
      .subscribe({
        next: () => {
          this.rollbackConfirming.update((items) => ({ ...items, [revision.id]: false }));
          this.loadConfigurationHistory(robot.id);
          this.robotsService.list().subscribe((rows) => this.robots.set(rows));
        },
        error: () => this.rollbackErrors.update((errors) => ({
          ...errors, [revision.id]: 'Rollback could not be completed. The Robot may have changed since this revision.',
        })),
      });
  }

  // ---- Phase 17M: post-change safety monitoring & human-governed rollback recommendations ----

  protected loadSafety(robotId: string): void {
    this.robotsService.postChangeSafety(robotId).subscribe({
      next: (rows) => this.safetyByRobot.update((byRobot) => ({ ...byRobot, [robotId]: rows })),
      error: () => this.safetyByRobot.update((byRobot) => ({ ...byRobot, [robotId]: [] })),
    });
  }

  protected safetyFor(robot: RobotSummary, revision: RobotConfigurationRevision): RevisionSafety | null {
    return (this.safetyByRobot()[robot.id] ?? []).find((s) => s.revisionId === revision.id) ?? null;
  }

  protected safetyEvaluation(safety: RevisionSafety, window: SafetyWindow): PostChangeSafetyEvaluation | null {
    return safety.latestEvaluations.find((e) => e.window === window) ?? null;
  }

  protected safetyStatusLabel(status: PostChangeSafetyEvaluation['status']): string {
    switch (status) {
      case 'READY_STABLE': return 'No material adverse difference observed';
      case 'READY_REGRESSION_OBSERVED': return 'Material adverse difference observed';
      case 'TOO_YOUNG': return 'Too early: no mature post-change publications yet';
      case 'METRIC_UNAVAILABLE': return 'Metric unavailable';
      case 'INSUFFICIENT_SAMPLE': return 'Insufficient sample';
      case 'LOW_COVERAGE': return 'Coverage too low';
      case 'NOT_COMPARABLE': return 'Not comparable (provider mismatch)';
      case 'BASELINE_UNAVAILABLE': return 'Baseline unavailable';
      case 'SUPERSEDED': return 'Superseded by a later configuration change';
    }
  }

  protected percent(value: number | null): string { return value === null || value === undefined ? 'n/a' : value.toFixed(1) + '%'; }
  protected fixed(value: number | null): string { return value === null || value === undefined ? 'n/a' : String(Math.round(value * 100) / 100); }

  protected openRecommendationFor(robot: RobotSummary): boolean {
    return (this.safetyByRobot()[robot.id] ?? []).some((s) => s.recommendation?.status === 'OPEN' || s.recommendation?.status === 'ACKNOWLEDGED');
  }

  protected reviewRecommendation(recommendation: RollbackRecommendation, reviewing: boolean): void {
    this.recommendationReviewing.update((items) => ({ ...items, [recommendation.id]: reviewing }));
  }

  protected acknowledgeRecommendation(robot: RobotSummary, recommendation: RollbackRecommendation): void {
    this.recommendationAction(robot, recommendation, this.robotsService.acknowledgeRollbackRecommendation(recommendation.id));
  }

  protected dismissRecommendation(robot: RobotSummary, recommendation: RollbackRecommendation): void {
    this.recommendationAction(robot, recommendation, this.robotsService.dismissRollbackRecommendation(recommendation.id));
  }

  protected rollbackRecommendation(robot: RobotSummary, recommendation: RollbackRecommendation): void {
    this.recommendationAction(robot, recommendation, this.robotsService.rollbackFromRecommendation(recommendation.id, null), true);
  }

  private recommendationAction(robot: RobotSummary, recommendation: RollbackRecommendation,
      call: ReturnType<RobotsService['acknowledgeRollbackRecommendation']>, refreshRobot = false): void {
    this.recommendationErrors.update((errors) => ({ ...errors, [recommendation.id]: null }));
    this.recommendationBusy.update((busy) => ({ ...busy, [recommendation.id]: true }));
    call.pipe(finalize(() => this.recommendationBusy.update((busy) => ({ ...busy, [recommendation.id]: false })))).subscribe({
      next: () => {
        this.recommendationReviewing.update((items) => ({ ...items, [recommendation.id]: false }));
        this.loadSafety(robot.id);
        if (refreshRobot) {
          this.loadConfigurationHistory(robot.id);
          this.robotsService.list().subscribe((rows) => this.robots.set(rows));
        }
      },
      error: () => {
        this.recommendationErrors.update((errors) => ({
          ...errors, [recommendation.id]: 'This action could not be completed. The Robot configuration may have changed.',
        }));
        this.loadSafety(robot.id);
      },
    });
  }

  protected changeTypeLabel(type: RobotConfigurationRevision['changeType']): string {
    return type === 'ROLLBACK' ? 'Rolled back' : 'Persona change';
  }

  protected executionOriginLabel(origin: RobotConfigurationRevision['executionOrigin'] | undefined): string {
    switch (origin) {
      case 'PREAUTHORIZED_AUTO_APPLY': return 'Applied automatically (pre-authorized)';
      case 'HUMAN_ROLLBACK': return 'Human rollback';
      default: return 'Applied by a person';
    }
  }

  protected isAdaptivePolicyExpanded(robot: RobotSummary): boolean { return this.adaptivePolicyExpanded()[robot.id] ?? false; }
  protected toggleAdaptivePolicy(robot: RobotSummary): void {
    const expanded = !this.isAdaptivePolicyExpanded(robot);
    this.adaptivePolicyExpanded.update((v) => ({ ...v, [robot.id]: expanded }));
    if (expanded && !this.adaptivePolicies()[robot.id]) this.loadAdaptivePolicy(robot.id);
    if (expanded) this.loadAdaptiveMemory(robot.id);
  }
  protected adaptivePolicyFor(robot: RobotSummary): RobotAdaptivePolicy | null { return this.adaptivePolicies()[robot.id] ?? null; }
  protected adaptivePolicyHistoryFor(robot: RobotSummary): RobotAdaptivePolicyRevision[] { return this.adaptivePolicyHistory()[robot.id] ?? []; }
  protected updateAdaptivePolicyField(robot: RobotSummary, field: keyof RobotAdaptivePolicy, value: boolean | number | 'MANUAL_ONLY' | 'AUTO_PROPOSE'): void {
    const policy = this.adaptivePolicyFor(robot); if (!policy) return;
    this.adaptivePolicies.update((all) => ({ ...all, [robot.id]: { ...policy, [field]: value } }));
  }
  protected checkAdaptiveProposalEligibility(robot: RobotSummary): void {
    this.adaptiveProposalEligibilityBusy.update((v) => ({ ...v, [robot.id]: true }));
    this.robotsService.adaptiveProposalEligibility(robot.id)
      .pipe(finalize(() => this.adaptiveProposalEligibilityBusy.update((v) => ({ ...v, [robot.id]: false }))))
      .subscribe({
        next: (result) => { this.adaptiveProposalEligibility.update((v) => ({ ...v, [robot.id]: result })); this.loadAdaptiveMemory(robot.id); },
        error: () => this.adaptivePolicyErrors.update((v) => ({ ...v, [robot.id]: 'Eligibility could not be checked.' })),
      });
  }
  protected saveAdaptivePolicy(robot: RobotSummary): void {
    const policy=this.adaptivePolicyFor(robot); if(!policy)return;
    this.adaptivePolicyBusy.update(v=>({...v,[robot.id]:true}));this.adaptivePolicyErrors.update(v=>({...v,[robot.id]:null}));
    this.robotsService.updateAdaptivePolicy(robot.id, policy)
      .pipe(finalize(() => this.adaptivePolicyBusy.update((v) => ({ ...v, [robot.id]: false }))))
      .subscribe({
        next: (saved) => { this.adaptivePolicies.update((v) => ({ ...v, [robot.id]: saved })); this.loadAdaptivePolicyHistory(robot.id); },
        error: () => this.adaptivePolicyErrors.update((v) => ({ ...v, [robot.id]: 'Policy update failed. Reload before retrying.' })),
      });
  }
  // ---- Phase 17N: read-only adaptive history (deterministic transition memory; never a score or ranking) ----

  protected loadAdaptiveMemory(robotId: string): void {
    this.robotsService.adaptiveMemory(robotId).subscribe({
      next: (memory) => this.adaptiveMemoryByRobot.update((all) => ({ ...all, [robotId]: memory })),
      error: () => this.adaptiveMemoryByRobot.update((all) => ({ ...all, [robotId]: { robotId, currentPersonaId: null, engineVersion: '', transitions: [], totalTransitions: 0 } })),
    });
  }

  protected adaptiveMemoryFor(robot: RobotSummary): AdaptiveMemoryTransition[] { return this.adaptiveMemoryByRobot()[robot.id]?.transitions ?? []; }

  protected memoryOutcomeLabel(outcome: MemoryOutcome): string {
    switch (outcome) {
      case 'PROPOSED': return 'Proposed';
      case 'HUMAN_REJECTED': return 'A previous proposal for this transition was rejected.';
      case 'APPROVED_NOT_APPLIED': return 'Approved, not applied';
      case 'APPLIED': return 'Applied';
      case 'OBSERVED_STABLE': return 'Applied; no material adverse difference observed';
      case 'OBSERVED_REGRESSION': return 'Applied; a material adverse difference was observed';
      case 'ROLLED_BACK': return 'An earlier application of this transition was rolled back by a person.';
      case 'SUPERSEDED': return 'Applied, then superseded by a later change';
    }
  }

  protected memoryReasonText(reason: SuppressionReason): string {
    switch (reason) {
      case 'RECENTLY_PROPOSED': return 'This transition was proposed recently.';
      case 'HUMAN_REJECTED': return 'A previous proposal for this transition was rejected.';
      case 'RECENTLY_APPLIED': return 'This transition was applied recently.';
      case 'OBSERVED_REGRESSION': return 'A material adverse difference was observed after this transition. This does not prove the Persona change caused the outcome.';
      case 'ROLLED_BACK': return 'An earlier application of this transition was rolled back by a person.';
      case 'CURRENTLY_ACTIVE': return 'This Persona is already the Robot\'s current Persona.';
    }
  }

  protected skippedCandidateName(robot: RobotSummary, personaId: string): string {
    const known = this.adaptiveMemoryFor(robot).find((t) => t.toPersonaId === personaId);
    return known?.toPersonaName ?? personaId;
  }

  private loadAdaptivePolicy(robotId:string):void {
    this.robotsService.adaptivePolicy(robotId).subscribe({
      next: (p) => this.adaptivePolicies.update((v) => ({ ...v, [robotId]: p })),
      error: () => this.adaptivePolicyErrors.update((v) => ({ ...v, [robotId]: 'Adaptive policy could not be loaded.' })),
    });
    this.loadAdaptivePolicyHistory(robotId);
  }
  private loadAdaptivePolicyHistory(robotId:string):void {
    this.robotsService.adaptivePolicyHistory(robotId).subscribe({
      next: (h) => this.adaptivePolicyHistory.update((v) => ({ ...v, [robotId]: h })),
      error: () => this.adaptivePolicyHistory.update((v) => ({ ...v, [robotId]: [] })),
    });
  }
}
