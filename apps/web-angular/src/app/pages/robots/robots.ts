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
} from '../../core/robots/robot.models';
import { ContentSourcesService } from '../../core/content-sources/content-sources.service';
import { ContentSourceSummary } from '../../core/content-sources/content-source.models';
import { PersonasService } from '../../core/personas/personas.service';
import { PersonaSummary } from '../../core/personas/persona.models';
import { SuggestionLanguage, SuggestionTone } from '../../core/content-suggestions/content-suggestion.models';
import { ExperimentsService } from '../../core/experiments/experiments.service';
import { ExperimentSummary } from '../../core/experiments/experiment.models';

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
  protected readonly createBusy = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly approveBusy = signal<Record<string, boolean>>({});
  protected readonly approveErrors = signal<Record<string, string | null>>({});
  protected readonly rejectBusy = signal<Record<string, boolean>>({});

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
    this.expandedRobots.update((items) => ({ ...items, [robot.id]: !(items[robot.id] ?? false) }));
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
    return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED';
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
}
