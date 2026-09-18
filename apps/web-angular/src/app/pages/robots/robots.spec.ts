import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { AssetsService } from '../../core/assets/assets.service';
import { MediaAssetSummary } from '../../core/assets/asset.models';
import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import { SocialAccountSummary } from '../../core/social-accounts/social-account.models';
import { RobotsService } from '../../core/robots/robots.service';
import { RobotApprovalsService } from '../../core/robots/robot-approvals.service';
import { RobotApprovalSummary, RobotRunSummary, RobotSummary } from '../../core/robots/robot.models';
import { ContentSourcesService } from '../../core/content-sources/content-sources.service';
import { ContentSourceSummary } from '../../core/content-sources/content-source.models';
import { Robots } from './robots';

describe('Robots', () => {
  let component: Robots;
  let fixture: ComponentFixture<Robots>;
  let robotsService: Pick<RobotsService, 'list' | 'create' | 'runNow' | 'pause' | 'resume' | 'runsForRobot' | 'allRuns' | 'cancelRun'>;
  let approvalsService: Pick<RobotApprovalsService, 'list' | 'approve' | 'reject'>;
  let assetsService: Pick<AssetsService, 'list'>;
  let socialAccountsService: Pick<SocialAccountsService, 'list'>;
  let contentSourcesService: Pick<ContentSourcesService, 'list'>;

  beforeEach(async () => {
    robotsService = {
      list: vi.fn().mockReturnValue(of([])),
      create: vi.fn().mockReturnValue(of(robot('ACTIVE', 'DRAFT_ONLY'))),
      runNow: vi.fn().mockReturnValue(of(run('RUNNING'))),
      pause: vi.fn().mockReturnValue(of(robot('PAUSED', 'DRAFT_ONLY'))),
      resume: vi.fn().mockReturnValue(of(robot('ACTIVE', 'DRAFT_ONLY'))),
      runsForRobot: vi.fn().mockReturnValue(of([])),
      allRuns: vi.fn().mockReturnValue(of([])),
      cancelRun: vi.fn().mockReturnValue(of(run('CANCELLED'))),
    };
    approvalsService = {
      list: vi.fn().mockReturnValue(of([])),
      approve: vi.fn().mockReturnValue(of(approval('APPROVED'))),
      reject: vi.fn().mockReturnValue(of(approval('REJECTED'))),
    };
    assetsService = {
      list: vi.fn().mockReturnValue(of([eligibleAsset()])),
    };
    socialAccountsService = {
      list: vi.fn().mockReturnValue(of([testAccount(), instagramAccount()])),
    };
    contentSourcesService = {
      list: vi.fn().mockReturnValue(of([contentSource()])),
    };

    await TestBed.configureTestingModule({
      imports: [Robots],
      providers: [
        { provide: RobotsService, useValue: robotsService },
        { provide: RobotApprovalsService, useValue: approvalsService },
        { provide: AssetsService, useValue: assetsService },
        { provide: SocialAccountsService, useValue: socialAccountsService },
        { provide: ContentSourcesService, useValue: contentSourcesService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('shows the empty robots state', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No robots yet.');
  });

  it('shows an error state when robots fail to load', async () => {
    vi.mocked(robotsService.list).mockReturnValue(throwError(() => new Error('Network failure')));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Robots could not be loaded.');
  });

  it('creates a DRAFT_ONLY robot without requiring an account', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['createName'].set('Romanian Tech Clips');
    component['createSourceAssetId'].set('asset-1');

    component['createRobot']();

    expect(robotsService.create).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Romanian Tech Clips',
      autonomyMode: 'DRAFT_ONLY',
      sourceAssetId: 'asset-1',
      targetSocialAccountId: null,
      scheduleDelayMinutes: null,
    }));
  });

  it('requires an account for AUTO_SCHEDULE and only offers TEST accounts', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['createName'].set('Auto Robot');
    component['createSourceAssetId'].set('asset-1');
    component['createAutonomyMode'].set('AUTO_SCHEDULE');

    expect(component['accountOptionsFor']('AUTO_SCHEDULE').every((account) => account.platform === 'TEST')).toBe(true);

    component['createRobot']();

    expect(robotsService.create).not.toHaveBeenCalled();
    expect(component['createError']()).toBe('Choose a target account for this autonomy mode.');
  });

  it('creates a CONTENT_SOURCE robot with a selection policy', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['createName'].set('Dynamic Robot');
    component['createSourcePolicy'].set('CONTENT_SOURCE');
    component['createContentSourceId'].set('source-1');
    component['createSelectionPolicy'].set('NEWEST_UNPROCESSED');

    component['createRobot']();

    expect(robotsService.create).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Dynamic Robot',
      sourcePolicy: 'CONTENT_SOURCE',
      sourceAssetId: null,
      contentSourceId: 'source-1',
      selectionPolicy: 'NEWEST_UNPROCESSED',
    }));
  });

  it('requires a content source when source type is CONTENT_SOURCE', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['createName'].set('Dynamic Robot');
    component['createSourcePolicy'].set('CONTENT_SOURCE');

    component['createRobot']();

    expect(robotsService.create).not.toHaveBeenCalled();
    expect(component['createError']()).toBe('Choose a content source.');
  });

  it('shows a human-readable selection policy label', () => {
    fixture.detectChanges();

    expect(component['selectionPolicyLabel']('OLDEST_UNPROCESSED')).toBe('Oldest unprocessed');
    expect(component['selectionPolicyLabel']('NEWEST_UNPROCESSED')).toBe('Newest unprocessed');
  });

  it('shows the content source name and selection policy on a dynamic robot card', () => {
    const dynamicRobot: RobotSummary = {
      ...robot('ACTIVE', 'DRAFT_ONLY'),
      sourcePolicy: 'CONTENT_SOURCE',
      sourceAssetId: null,
      sourceAssetFilename: null,
      contentSourceId: 'source-1',
      contentSourceName: 'Incoming Tech Videos',
      selectionPolicy: 'OLDEST_UNPROCESSED',
    };
    vi.mocked(robotsService.list).mockReturnValue(of([dynamicRobot]));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Incoming Tech Videos');
    expect(text).toContain('Oldest unprocessed');
    expect(text).not.toContain('undefined');
  });

  it('allows REVIEW_REQUIRED to target an Instagram account', () => {
    fixture.detectChanges();

    const options = component['accountOptionsFor']('REVIEW_REQUIRED');

    expect(options.some((account) => account.platform === 'INSTAGRAM')).toBe(true);
  });

  it('runs a robot now and shows the resulting run', () => {
    vi.mocked(robotsService.list).mockReturnValue(of([robot('ACTIVE', 'DRAFT_ONLY')]));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const theRobot = component['robots']()[0];

    component['runNow'](theRobot);

    expect(robotsService.runNow).toHaveBeenCalledWith(theRobot.id);
    expect(component['runs']()[0].status).toBe('RUNNING');
  });

  it('surfaces a Run Now failure', () => {
    vi.mocked(robotsService.runNow).mockReturnValue(throwError(() => new Error('boom')));
    vi.mocked(robotsService.list).mockReturnValue(of([robot('ACTIVE', 'DRAFT_ONLY')]));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const theRobot = component['robots']()[0];

    component['runNow'](theRobot);

    expect(component['runNowErrors']()[theRobot.id]).toBe('Run could not be started.');
  });

  it('pauses an active robot and resumes a paused one', () => {
    vi.mocked(robotsService.list).mockReturnValue(of([robot('ACTIVE', 'DRAFT_ONLY')]));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const theRobot = component['robots']()[0];

    component['togglePause'](theRobot);

    expect(robotsService.pause).toHaveBeenCalledWith(theRobot.id);
    expect(component['robots']()[0].status).toBe('PAUSED');

    component['togglePause'](component['robots']()[0]);

    expect(robotsService.resume).toHaveBeenCalled();
  });

  it('shows run history with status and null-safe rendering when expanded', () => {
    vi.mocked(robotsService.list).mockReturnValue(of([robot('ACTIVE', 'DRAFT_ONLY')]));
    vi.mocked(robotsService.allRuns).mockReturnValue(of([run('FAILED')]));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const theRobot = component['robots']()[0];
    component['toggleExpanded'](theRobot);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Failed');
    expect(text).toContain('SOURCE_UNAVAILABLE');
    expect(text).not.toContain('undefined');
    expect(text).not.toContain('NaN');
    expect(text).not.toContain('Invalid Date');
  });

  it('shows an understandable message for a NO_ELIGIBLE_SOURCE run', () => {
    const emptySourceRun: RobotRunSummary = {
      ...run('FAILED'),
      sourceAssetId: null,
      contentSourceId: 'source-1',
      contentSourceName: 'Incoming Tech Videos',
      selectionPolicy: 'OLDEST_UNPROCESSED',
      failureCode: 'NO_ELIGIBLE_SOURCE',
      failureMessage: 'No eligible unprocessed asset was found in this content source',
    };
    vi.mocked(robotsService.list).mockReturnValue(of([robot('ACTIVE', 'DRAFT_ONLY')]));
    vi.mocked(robotsService.allRuns).mockReturnValue(of([emptySourceRun]));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const theRobot = component['robots']()[0];
    component['toggleExpanded'](theRobot);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('NO_ELIGIBLE_SOURCE');
    expect(text).toContain('nothing eligible');
    expect(text).not.toContain('undefined');
  });

  it('shows the empty approvals state', () => {
    fixture.detectChanges();
    component['switchView']('approvals');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No approvals yet.');
  });

  it('shows a pending approval with caption, account, and proposed time', () => {
    vi.mocked(approvalsService.list).mockReturnValue(of([approval('PENDING')]));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['switchView']('approvals');
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Pending');
    expect(text).toContain('Proposed caption');
    expect(text).toContain('TEST account');
  });

  it('approves a pending approval', () => {
    vi.mocked(approvalsService.list).mockReturnValue(of([approval('PENDING')]));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const pending = component['approvals']()[0];

    component['approve'](pending);

    expect(approvalsService.approve).toHaveBeenCalledWith(pending.id, null);
    expect(component['approvals']()[0].status).toBe('APPROVED');
  });

  it('rejects a pending approval', () => {
    vi.mocked(approvalsService.list).mockReturnValue(of([approval('PENDING')]));
    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const pending = component['approvals']()[0];

    component['reject'](pending);

    expect(approvalsService.reject).toHaveBeenCalledWith(pending.id);
    expect(component['approvals']()[0].status).toBe('REJECTED');
  });

  function robot(status: RobotSummary['status'], autonomyMode: RobotSummary['autonomyMode']): RobotSummary {
    return {
      id: 'robot-1',
      name: 'Romanian Tech Clips',
      description: null,
      status,
      autonomyMode,
      highlightStrategy: 'TOP_HIGHLIGHT',
      sourcePolicy: 'EXISTING_ASSET',
      sourceAssetId: 'asset-1',
      sourceAssetFilename: 'video.mp4',
      contentSourceId: null,
      contentSourceName: null,
      selectionPolicy: null,
      targetSocialAccountId: null,
      targetSocialAccountDisplayName: null,
      cadenceType: 'MANUAL_ONLY',
      cadenceIntervalHours: null,
      scheduleDelayMinutes: null,
      maxRunsPerDay: 1,
      nextRunAt: null,
      lastRunAt: null,
      createdAt: '2026-09-18T08:00:00Z',
      updatedAt: '2026-09-18T08:00:00Z',
    };
  }

  function run(status: RobotRunSummary['status']): RobotRunSummary {
    return {
      id: 'run-1',
      robotId: 'robot-1',
      robotName: 'Romanian Tech Clips',
      triggerType: 'MANUAL',
      status,
      startedAt: '2026-09-18T08:05:00Z',
      finishedAt: status === 'RUNNING' ? null : '2026-09-18T08:06:00Z',
      sourceAssetId: 'asset-1',
      contentSourceId: null,
      contentSourceName: null,
      selectionPolicy: null,
      highlightAnalysisId: null,
      highlightCandidateId: null,
      contentDraftId: null,
      publishScheduleId: null,
      failureCode: status === 'FAILED' ? 'SOURCE_UNAVAILABLE' : null,
      failureMessage: status === 'FAILED' ? 'Source asset is not ready' : null,
      createdAt: '2026-09-18T08:05:00Z',
    };
  }

  function approval(status: RobotApprovalSummary['status']): RobotApprovalSummary {
    return {
      id: 'approval-1',
      robotRunId: 'run-1',
      robotId: 'robot-1',
      robotName: 'Romanian Tech Clips',
      contentDraftId: 'draft-1',
      draftTitle: 'My draft',
      draftCaption: 'Proposed caption',
      socialAccountId: 'account-1',
      socialAccountDisplayName: 'TEST account',
      proposedScheduledFor: '2026-09-18T09:00:00Z',
      status,
      createdAt: '2026-09-18T08:10:00Z',
      decidedAt: status === 'PENDING' ? null : '2026-09-18T08:15:00Z',
      decidedByUserId: status === 'PENDING' ? null : 'user-1',
      publishScheduleId: status === 'APPROVED' ? 'schedule-1' : null,
    };
  }

  function contentSource(): ContentSourceSummary {
    return {
      id: 'source-1',
      name: 'Incoming Tech Videos',
      description: null,
      type: 'MEDIA_LIBRARY',
      status: 'ACTIVE',
      assetCount: 3,
      createdAt: '2026-09-18T07:00:00Z',
      updatedAt: '2026-09-18T07:00:00Z',
    };
  }

  function eligibleAsset(): MediaAssetSummary {
    return {
      id: 'asset-1',
      sourceType: 'DIRECT_URL',
      sourceUrl: 'https://example.com/video.mp4',
      parentAssetId: null,
      derivationType: 'ORIGINAL',
      status: 'READY',
      originalFilename: 'video.mp4',
      contentType: 'video/mp4',
      fileSizeBytes: 1_048_576,
      checksumSha256: '0'.repeat(64),
      durationMs: 12_000,
      width: 1920,
      height: 1080,
      videoCodec: 'h264',
      audioCodec: 'aac',
      containerFormat: 'mp4',
      importJobId: 'job-1',
      processingJobId: null,
      inspectionStatus: 'INSPECTED',
      inspectionJobId: 'job-2',
      inspectionErrorCode: null,
      inspectionErrorMessage: null,
      frameRate: 29.97,
      bitrate: 800_000,
      hasVideo: true,
      hasAudio: true,
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-09-18T08:00:00Z',
      updatedAt: '2026-09-18T08:00:00Z',
      readyAt: '2026-09-18T08:00:00Z',
    };
  }

  function testAccount(): SocialAccountSummary {
    return {
      id: 'account-1',
      platform: 'TEST',
      displayName: 'TEST account',
      externalAccountId: null,
      status: 'ACTIVE',
      createdAt: '2026-09-18T08:00:00Z',
      updatedAt: '2026-09-18T08:00:00Z',
    };
  }

  function instagramAccount(): SocialAccountSummary {
    return {
      id: 'account-2',
      platform: 'INSTAGRAM',
      displayName: 'Instagram creator',
      externalAccountId: 'ig-1',
      status: 'ACTIVE',
      createdAt: '2026-09-18T08:00:00Z',
      updatedAt: '2026-09-18T08:00:00Z',
    };
  }
});
