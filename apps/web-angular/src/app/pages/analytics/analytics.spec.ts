import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { PublishingService } from '../../core/publishing/publishing.service';
import { PublicationAnalyticsService } from '../../core/publishing/publication-analytics.service';
import { PublicationSummary } from '../../core/publishing/publishing.models';
import { PublicationAnalyticsSnapshot, PublicationAttribution } from '../../core/publishing/publication-analytics.models';
import { DashboardSummary } from '../../core/publishing/publication-dashboard.models';
import { ComparisonResult, InsightsResponse } from '../../core/publishing/publication-insights.models';
import { Analytics } from './analytics';

const publication = {
  id: 'publication-1', status: 'PUBLISHED', socialAccountDisplayName: 'TEST account',
  publishedAt: '2026-09-19T12:00:00Z', assetFilename: 'clip.mp4',
} as PublicationSummary;
const snapshot = {
  id: 'snapshot-1', publicationId: publication.id, provider: 'TEST', bucketKey: 'AGE:0',
  collectedAt: '2026-09-19T12:15:00Z', providerMetricVersion: 'TEST_ANALYTICS_V1',
  publicationAgeSeconds: 900, views: 0, reach: null, likes: 3, comments: 0, shares: null, saves: null,
} as PublicationAnalyticsSnapshot;
const origin = {
  publicationId: publication.id, robotNameSnapshot: null, robotRunId: null,
  sourceMediaAssetId: 'source-1', finalMediaAssetId: 'final-1',
  appliedContentSuggestionId: null, personaNameSnapshot: null,
} as PublicationAttribution;
const dashboard = {
  coverage: { publicationCount: 2, analyticsPublicationCount: 1, eligibleByAgeCount: 1,
    tooYoungCount: 1, missingSnapshotCount: 0 },
  metrics: { VIEWS: { total: 0, average: 0, median: 0, sampleCount: 1 },
    REACH: { total: null, average: null, median: null, sampleCount: 0 },
    SHARES: { total: null, average: null, median: null, sampleCount: 0 },
    TOTAL_INTERACTIONS: { total: null, average: null, median: null, sampleCount: 0 } },
} as DashboardSummary;
const insightsResponse = {
  engineVersion: 'PERFORMANCE_INSIGHTS_V1',
  filtersFingerprint: 'fp-1',
  disclaimer: 'These comparisons describe observed associations in the selected publications and do not establish causation.',
  notices: [{ type: 'MATURITY', message: '2 recent publication(s) have not yet reached the 72-hour observation window.' }],
  observations: [{
    id: 'obs-1', type: 'DIRECTIONAL_COMPARISON', engineVersion: 'PERFORMANCE_INSIGHTS_V1',
    dimension: 'ORIGIN', metric: 'VIEWS', statistic: 'MEDIAN', observationWindow: 'H72',
    left: { segmentId: 'MANUAL', label: 'Manual', publicationCount: 20, eligibleByAgeCount: 20,
      analyticsPublicationCount: 20, sampleCount: 20, coverage: 1, medianValue: 100, averageValue: 100 },
    right: { segmentId: 'ROBOT', label: 'Robot', publicationCount: 20, eligibleByAgeCount: 20,
      analyticsPublicationCount: 20, sampleCount: 20, coverage: 1, medianValue: 80, averageValue: 80 },
    absoluteDifference: 20, relativeDifferencePercent: 25, direction: 'HIGHER_OBSERVED', materialDifference: true,
    message: 'At the 72-hour observation window, Manual had a higher observed median views than Robot in this sample (100 vs 80; n=20 vs n=20).',
    limitations: ['These comparisons describe observed associations in the selected publications and do not establish causation.',
      'TEST analytics are deterministic development data, not real audience behavior.'],
    recommendations: [{ type: 'REVIEW_CONTENT_DIFFERENCES', message: 'Review the publications in both cohorts to identify content differences not represented by these analytics dimensions.' }],
  }, {
    id: 'obs-2', type: 'INSUFFICIENT_SAMPLE', engineVersion: 'PERFORMANCE_INSIGHTS_V1',
    dimension: 'AI_USAGE', metric: 'TOTAL_INTERACTIONS', statistic: 'MEDIAN', observationWindow: 'H72',
    left: { segmentId: 'AI_APPLIED', label: 'AI applied', publicationCount: 2, eligibleByAgeCount: 2,
      analyticsPublicationCount: 2, sampleCount: 2, coverage: 1, medianValue: null, averageValue: null },
    right: { segmentId: 'NO_APPLIED_AI', label: 'No applied AI', publicationCount: 20, eligibleByAgeCount: 20,
      analyticsPublicationCount: 20, sampleCount: 20, coverage: 1, medianValue: 80, averageValue: 80 },
    absoluteDifference: null, relativeDifferencePercent: null, direction: null, materialDifference: false,
    message: 'Not enough total interactions observations are available to compare AI applied and No applied AI at the 72-hour observation window (n=2 vs n=20; 5 required per segment).',
    limitations: ['These comparisons describe observed associations in the selected publications and do not establish causation.'],
    recommendations: [{ type: 'COLLECT_MORE_DATA', message: 'Collect more observations before comparing these segments.' }],
  }],
} as unknown as InsightsResponse;
const compareResult = {
  id: 'cmp-1', type: 'DIRECTIONAL_COMPARISON', engineVersion: 'PERFORMANCE_INSIGHTS_V1',
  dimension: 'ORIGIN', metric: 'VIEWS', statistic: 'MEDIAN', observationWindow: 'H72',
  left: { segmentId: 'MANUAL', label: 'Manual', publicationCount: 20, eligibleByAgeCount: 20,
    analyticsPublicationCount: 20, sampleCount: 20, coverage: 1, medianValue: 100, averageValue: 100 },
  right: { segmentId: 'ROBOT', label: 'Robot', publicationCount: 20, eligibleByAgeCount: 20,
    analyticsPublicationCount: 20, sampleCount: 20, coverage: 1, medianValue: 80, averageValue: 80 },
  absoluteDifference: 20, relativeDifferencePercent: 25, direction: 'HIGHER_OBSERVED', materialDifference: true,
  message: 'At the 72-hour observation window, Manual had a higher observed median views than Robot in this sample (100 vs 80; n=20 vs n=20).',
  limitations: ['These comparisons describe observed associations in the selected publications and do not establish causation.'],
  recommendations: [],
} as unknown as ComparisonResult;

describe('Analytics', () => {
  let fixture: ComponentFixture<Analytics>;
  let analytics: Pick<PublicationAnalyticsService, 'history' | 'attribution' | 'state' | 'refresh' |
    'dashboardSummary' | 'dashboardTrend' | 'dashboardBreakdown' | 'dashboardOptions' | 'insights' | 'compareSegments' |
    'campaignOptions' | 'campaignReviews' | 'campaignReview' | 'createCampaignReview' | 'campaignComparison'>;
  let publications: Subject<PublicationSummary[]>;

  beforeEach(async () => {
    publications = new Subject<PublicationSummary[]>();
    analytics = {
      history: vi.fn().mockReturnValue(of([snapshot])),
      attribution: vi.fn().mockReturnValue(of(origin)),
      state: vi.fn().mockReturnValue(of({ nextCollectionAt: null, completedAt: '2026-09-19T13:00:00Z',
        lastAttemptAt: null, lastSuccessAt: null, failureCode: null, failureMessage: null })),
      refresh: vi.fn().mockReturnValue(of(snapshot)),
      dashboardSummary: vi.fn().mockReturnValue(of(dashboard)),
      dashboardTrend: vi.fn().mockReturnValue(of({ metric: 'VIEWS', points: [{ date: '2026-09-19',
        coverage: dashboard.coverage, metric: dashboard.metrics.VIEWS }] })),
      dashboardBreakdown: vi.fn().mockReturnValue(of({ dimension: 'ROBOT', rows: [{ key: 'NONE',
        label: 'Manual / No Robot', coverage: dashboard.coverage, metrics: dashboard.metrics }], truncated: false })),
      dashboardOptions: vi.fn().mockReturnValue(of({ providers: ['TEST'], robots: [], personas: [], contentSources: [], truncated: false })),
      insights: vi.fn().mockReturnValue(of(insightsResponse)),
      compareSegments: vi.fn().mockReturnValue(of(compareResult)),
      campaignOptions: vi.fn().mockReturnValue(of([])),
      campaignReviews: vi.fn().mockReturnValue(of([])),
      campaignReview: vi.fn().mockReturnValue(of({})),
      createCampaignReview: vi.fn().mockReturnValue(of({})),
      campaignComparison: vi.fn().mockReturnValue(of({ dateFrom: '2026-08-22', dateTo: '2026-09-20',
        observationWindow: 'H72', metric: 'TOTAL_INTERACTIONS', dimension: 'ROLE', rows: [], truncated: false,
        recommendations: [], limitations: ['Observed associations do not establish causation.'] })),
    };
    await TestBed.configureTestingModule({
      imports: [Analytics],
      providers: [
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({ publicationId: publication.id })) } },
        { provide: Router, useValue: { navigate: vi.fn() } },
        { provide: PublishingService, useValue: { list: vi.fn().mockReturnValue(publications) } },
        { provide: PublicationAnalyticsService, useValue: analytics },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Analytics);
    fixture.detectChanges();
    await fixture.whenStable();
    publications.next([publication]);
    fixture.detectChanges();
  });

  it('selects a deep-linked publication after async options arrive', () => {
    expect((fixture.nativeElement.querySelector('#publication-select') as HTMLSelectElement).value)
      .toBe(publication.id);
  });

  it('renders campaign performance with neutral coverage, null and TEST limitation semantics', () => {
    const review = {
      id: 'review-1', robotRunId: '11111111-1111-1111-1111-111111111111', revision: 1,
      observationWindow: 'H72', primaryMetric: 'VIEWS', engineVersion: 'CAMPAIGN_PERFORMANCE_V1',
      recommendationEngineVersion: 'CAMPAIGN_RECOMMENDATIONS_V1', evidenceStatus: 'INSUFFICIENT_SAMPLE',
      robotRunStatus: 'SUCCEEDED', intendedOutputCount: 3, actualOutputCount: 3, publishedOutputCount: 3,
      failedOutputCount: 0, eligibleByAgeCount: 3, analyticsPublicationCount: 2,
      evidenceCutoffAt: '2026-09-20T12:00:00Z', createdAt: '2026-09-20T12:00:00Z',
      metrics: { VIEWS: { total: 0, average: 0, median: 0, minimum: 0, maximum: 0, sampleCount: 1 } },
      outputs: [{ id: 'evidence-1', robotRunOutputId: 'output-1', selectionOrder: 1, sourceRank: 1,
        outputStatus: 'SUCCEEDED', campaignRole: 'INTRODUCTION', highlightCandidateId: 'candidate-1',
        campaignPlanId: 'plan-1', campaignPlanRevision: 1, campaignPlanItemId: 'plan-item-1', campaignCopySetId: 'copy-1',
        campaignCopySetRevision: 1, campaignCopyItemId: 'copy-item-1', contentSuggestionId: 'suggestion-1',
        contentDraftId: 'draft-1', publishScheduleId: 'schedule-1', publicationId: 'publication-1', provider: 'TEST',
        publishedAt: '2026-09-16T12:00:00Z', analyticsSnapshotId: 'snapshot-1', evidenceStatus: 'OBSERVED',
        metrics: { VIEWS: 0 } }], comparisons: [],
      recommendations: [{ id: 'rec-1', sequence: 1, type: 'COLLECT_MORE_DATA', metric: 'VIEWS',
        comparedDimension: null, evidence: { sampleCount: 1 }, message: 'Collect more comparable observations.', limitations: [] }],
      limitations: ['Observed associations do not establish causation.',
        'TEST publishing and analytics are deterministic synthetic data, not representative of real social-platform engagement.'],
    };
    (fixture.componentInstance as any).tab.set('campaigns');
    (fixture.componentInstance as any).campaignReview.set(review as any);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Campaign Performance');
    expect(text).toContain('Output 1');
    expect(text).toContain('INTRODUCTION');
    expect(text).toContain('synthetic and deterministic');
    expect(text).toContain('Observed associations do not establish causation');
    expect(text.toLowerCase()).not.toContain('winner');
    expect(text.toLowerCase()).not.toContain('best-performing');
  });

  it('creates a campaign performance review for the selected run/window/metric and reloads it', () => {
    (fixture.componentInstance as any).tab.set('campaigns');
    (fixture.componentInstance as any).campaignRunId.set('run-1');
    (fixture.componentInstance as any).campaignWindow.set('H72');
    (fixture.componentInstance as any).campaignMetric.set('VIEWS');
    fixture.detectChanges();

    (fixture.componentInstance as any).createCampaignReview();

    expect(analytics.createCampaignReview).toHaveBeenCalledWith('run-1', 'H72', 'VIEWS');
  });

  it('renders the historical cohort comparison table with neutral segment labels, no leaderboard styling', () => {
    (fixture.componentInstance as any).tab.set('campaigns');
    (fixture.componentInstance as any).campaignComparison.set({
      dateFrom: '2026-08-22', dateTo: '2026-09-20', observationWindow: 'H72', metric: 'VIEWS', dimension: 'ROLE',
      rows: [
        { key: 'INTRODUCTION', label: 'INTRODUCTION', publicationCount: 10, eligibleByAgeCount: 10, analyticsPublicationCount: 10,
          sampleCount: 10, coverage: 1, metric: { total: 1000, average: 100, median: 95, minimum: 50, maximum: 150, sampleCount: 10 } },
        { key: 'CONCLUSION', label: 'CONCLUSION', publicationCount: 10, eligibleByAgeCount: 10, analyticsPublicationCount: 10,
          sampleCount: 10, coverage: 1, metric: { total: 800, average: 80, median: 78, minimum: 40, maximum: 120, sampleCount: 10 } },
      ],
      truncated: false,
      recommendations: [{ id: 'rec-cohort', sequence: 1, type: 'CONSIDER_CONTROLLED_EXPERIMENT', metric: 'VIEWS',
        comparedDimension: 'ROLE', evidence: {}, message: 'Consider a separately designed controlled experiment.', limitations: [] }],
      limitations: ['Observed associations do not establish causation.'],
    });
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('INTRODUCTION');
    expect(text).toContain('CONCLUSION');
    expect(text).toContain('Consider a separately designed controlled experiment');
    expect(text.toLowerCase()).not.toContain('winner');
    const rowLabels = Array.from(fixture.nativeElement.querySelectorAll('th[scope="row"]')).map((el: any) => el.textContent.trim());
    expect(rowLabels).toEqual(['INTRODUCTION', 'CONCLUSION']);
  });

  it('renders observed zero separately from unavailable metrics, history and manual attribution', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Latest observation');
    expect(text).toContain('TEST_ANALYTICS_V1');
    expect(text).toContain('0');
    expect(text).toContain('—');
    expect(text).toContain('Manual');
    expect(text).toContain('Collection complete');
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(2);
  });

  it('shows frozen Robot, Persona and provider provenance', () => {
    (analytics.attribution as ReturnType<typeof vi.fn>).mockReturnValue(of({
      ...origin, robotRunId: 'run-1', robotNameSnapshot: 'Historical Robot',
      appliedContentSuggestionId: 'suggestion-1', personaNameSnapshot: 'Original Persona',
      suggestionOrigin: 'ROBOT', aiProvider: 'TEST', aiModel: 'model-v1', promptVersion: 'v1',
    }));
    (fixture.componentInstance as any).load(publication.id);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Historical Robot');
    expect(text).toContain('Original Persona');
    expect(text).toContain('model-v1');
    expect(text).toContain('v1');
  });

  it('shows the frozen ContentSource name snapshot rather than a raw ID', () => {
    (analytics.attribution as ReturnType<typeof vi.fn>).mockReturnValue(of({
      ...origin, contentSourceId: 'source-abc', contentSourceNameSnapshot: 'Historical Source',
    }));
    (fixture.componentInstance as any).load(publication.id);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Historical Source');
    expect(text).not.toContain('source-abc');
  });

  it('falls back to "ContentSource name unavailable" when the snapshot is missing', () => {
    (analytics.attribution as ReturnType<typeof vi.fn>).mockReturnValue(of({
      ...origin, contentSourceId: 'source-abc', contentSourceNameSnapshot: null,
    }));
    (fixture.componentInstance as any).load(publication.id);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('ContentSource name unavailable');
  });

  it('refreshes and reports too-soon errors safely', () => {
    (analytics.refresh as ReturnType<typeof vi.fn>).mockReturnValue(
      throwError(() => ({ status: 429 })));
    (fixture.componentInstance as any).refresh();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Please wait before refreshing');
  });

  it('shows dashboard coverage and keeps observed zero distinct from missing interactions', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('1 / 2 publications');
    expect(text).toContain('1 have not reached');
    expect(text).toContain('Total views');
    expect(text).toContain('Total interactions');
    expect(text).toContain('n=0');
    expect(text).toContain('Publication cohort trend');
    expect(text).toContain('Manual / No Robot');
  });

  describe('Insights tab', () => {
    beforeEach(() => {
      (fixture.componentInstance as any).tab.set('insights');
      fixture.detectChanges();
    });

    it('shows the causality disclaimer, maturity notices, and bounded automatic observations', () => {
      const text = fixture.nativeElement.textContent as string;
      expect(text).toContain('do not establish causation');
      expect(text).toContain('2 recent publication(s) have not yet reached');
      expect(text).toContain('Manual');
      expect(text).toContain('Robot');
      expect(text).toContain('Higher observed');
      expect(fixture.nativeElement.querySelectorAll('.analytics__insight-card').length).toBe(2);
    });

    it('never uses winner/loser/better/worse language anywhere in the rendered insights', () => {
      const text = (fixture.nativeElement.textContent as string).toLowerCase();
      expect(text).not.toContain('winner');
      expect(text).not.toContain('loser');
      expect(text).not.toContain('better');
      expect(text).not.toContain('worse');
      expect(text).not.toContain('best persona');
      expect(text).not.toContain('best robot');
    });

    it('renders an insufficient-sample observation with its evidence-bound recommendation, not a directional claim', () => {
      const text = fixture.nativeElement.textContent as string;
      expect(text).toContain('Not enough total interactions observations');
      expect(text).toContain('Collect more observations before comparing');
      expect(text).toContain('Not enough observations yet');
    });

    it('shows the TEST-data limitation for TEST-backed observations', () => {
      const text = fixture.nativeElement.textContent as string;
      expect(text).toContain('TEST analytics are deterministic development data');
    });

    it('runs an explicit comparison and renders its evidence without winner styling', () => {
      const component = fixture.componentInstance as any;
      component.compareDimension.set('ORIGIN');
      component.compareLeft.set('MANUAL');
      component.compareRight.set('ROBOT');
      component.runCompare(component.filters());
      fixture.detectChanges();
      expect(analytics.compareSegments).toHaveBeenCalledWith(expect.objectContaining({
        dimension: 'ORIGIN', leftSegmentId: 'MANUAL', rightSegmentId: 'ROBOT', metric: 'VIEWS', statistic: 'MEDIAN',
      }));
      const text = fixture.nativeElement.textContent as string;
      expect(text).toContain('100');
      expect(text).toContain('80');
      expect(text).toContain('25');
    });

    it('rejects comparing a segment to itself client-side without calling the API', () => {
      const component = fixture.componentInstance as any;
      (analytics.compareSegments as ReturnType<typeof vi.fn>).mockClear();
      component.compareLeft.set('MANUAL');
      component.compareRight.set('MANUAL');
      component.runCompare(component.filters());
      fixture.detectChanges();
      expect(analytics.compareSegments).not.toHaveBeenCalled();
      expect(fixture.nativeElement.textContent).toContain('Choose two different segments to compare');
    });
  });
});
