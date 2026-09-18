import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EMPTY, Subscription, catchError, finalize, interval, startWith, switchMap } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { MediaAssetSummary } from '../../core/assets/asset.models';
import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import { SocialAccountSummary } from '../../core/social-accounts/social-account.models';
import { RobotsService } from '../../core/robots/robots.service';
import { RobotApprovalsService } from '../../core/robots/robot-approvals.service';
import {
  RobotAutonomyMode,
  RobotCadenceType,
  RobotRunSummary,
  RobotSummary,
  RobotApprovalSummary,
} from '../../core/robots/robot.models';

type LoadState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-robots',
  imports: [DatePipe, FormsModule],
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

  protected readonly expandedRobots = signal<Record<string, boolean>>({});
  protected readonly runNowBusy = signal<Record<string, boolean>>({});
  protected readonly runNowErrors = signal<Record<string, string | null>>({});
  protected readonly pauseResumeBusy = signal<Record<string, boolean>>({});

  protected readonly showCreateForm = signal(false);
  protected readonly createName = signal('');
  protected readonly createDescription = signal('');
  protected readonly createAutonomyMode = signal<RobotAutonomyMode>('DRAFT_ONLY');
  protected readonly createSourceAssetId = signal('');
  protected readonly createTargetAccountId = signal('');
  protected readonly createCadenceType = signal<RobotCadenceType>('MANUAL_ONLY');
  protected readonly createCadenceIntervalHours = signal(24);
  protected readonly createScheduleDelayMinutes = signal(60);
  protected readonly createMaxRunsPerDay = signal(1);
  protected readonly createBusy = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly approveBusy = signal<Record<string, boolean>>({});
  protected readonly approveErrors = signal<Record<string, string | null>>({});
  protected readonly rejectBusy = signal<Record<string, boolean>>({});

  private robotsSubscription?: Subscription;
  private approvalsSubscription?: Subscription;

  constructor(
    private readonly robotsService: RobotsService,
    private readonly approvalsService: RobotApprovalsService,
    private readonly assetsService: AssetsService,
    private readonly socialAccountsService: SocialAccountsService,
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

    this.robotsService
      .allRuns()
      .pipe(catchError(() => EMPTY))
      .subscribe((runs) => this.runs.set(runs));
  }

  ngOnDestroy(): void {
    this.robotsSubscription?.unsubscribe();
    this.approvalsSubscription?.unsubscribe();
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

  protected createRobot(): void {
    this.createError.set(null);
    const name = this.createName().trim();
    if (!name) {
      this.createError.set('Name is required.');
      return;
    }
    if (!this.createSourceAssetId()) {
      this.createError.set('Choose a source asset.');
      return;
    }
    const mode = this.createAutonomyMode();
    if (this.requiresAccount(mode) && !this.createTargetAccountId()) {
      this.createError.set('Choose a target account for this autonomy mode.');
      return;
    }
    this.createBusy.set(true);
    this.robotsService
      .create({
        name,
        description: this.createDescription().trim() || null,
        autonomyMode: mode,
        sourceAssetId: this.createSourceAssetId(),
        targetSocialAccountId: this.requiresAccount(mode) ? this.createTargetAccountId() : null,
        cadenceType: this.createCadenceType(),
        cadenceIntervalHours: this.createCadenceType() === 'INTERVAL' ? this.createCadenceIntervalHours() : null,
        scheduleDelayMinutes: this.requiresDelay(mode) ? this.createScheduleDelayMinutes() : null,
        maxRunsPerDay: this.createMaxRunsPerDay(),
      })
      .pipe(finalize(() => this.createBusy.set(false)))
      .subscribe({
        next: (robot) => {
          this.robots.set([robot, ...this.robots()]);
          this.showCreateForm.set(false);
          this.createName.set('');
          this.createDescription.set('');
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

  protected runStatusLabel(status: RobotRunSummary['status']): string {
    switch (status) {
      case 'RUNNING':
        return 'Running';
      case 'WAITING_FOR_DRAFT':
        return 'Preparing draft';
      case 'WAITING_FOR_REVIEW':
        return 'Waiting for review';
      case 'SUCCEEDED':
        return 'Succeeded';
      case 'FAILED':
        return 'Failed';
      case 'CANCELLED':
        return 'Cancelled';
    }
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
