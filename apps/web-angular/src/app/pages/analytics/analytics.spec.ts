import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { PublishingService } from '../../core/publishing/publishing.service';
import { PublicationAnalyticsService } from '../../core/publishing/publication-analytics.service';
import { PublicationSummary } from '../../core/publishing/publishing.models';
import { PublicationAnalyticsSnapshot, PublicationAttribution } from '../../core/publishing/publication-analytics.models';
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

describe('Analytics', () => {
  let fixture: ComponentFixture<Analytics>;
  let analytics: Pick<PublicationAnalyticsService, 'history' | 'attribution' | 'state' | 'refresh'>;
  let publications: Subject<PublicationSummary[]>;

  beforeEach(async () => {
    publications = new Subject<PublicationSummary[]>();
    analytics = {
      history: vi.fn().mockReturnValue(of([snapshot])),
      attribution: vi.fn().mockReturnValue(of(origin)),
      state: vi.fn().mockReturnValue(of({ nextCollectionAt: null, completedAt: '2026-09-19T13:00:00Z',
        lastAttemptAt: null, lastSuccessAt: null, failureCode: null, failureMessage: null })),
      refresh: vi.fn().mockReturnValue(of(snapshot)),
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

  it('renders observed zero separately from unavailable metrics, history and manual attribution', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Latest observation');
    expect(text).toContain('TEST_ANALYTICS_V1');
    expect(text).toContain('0');
    expect(text).toContain('—');
    expect(text).toContain('Manual');
    expect(text).toContain('Collection complete');
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(1);
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

  it('refreshes and reports too-soon errors safely', () => {
    (analytics.refresh as ReturnType<typeof vi.fn>).mockReturnValue(
      throwError(() => ({ status: 429 })));
    (fixture.componentInstance as any).refresh();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Please wait before refreshing');
  });
});
