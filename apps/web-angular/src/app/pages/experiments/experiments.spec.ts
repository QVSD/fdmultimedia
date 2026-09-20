import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { ExperimentsService } from '../../core/experiments/experiments.service';
import {
  AnalysisStatus,
  ExperimentAnalysisResponse,
  ExperimentOutcome,
  ExperimentPopulationAnalysis,
  ExperimentSummary,
} from '../../core/experiments/experiment.models';
import { PersonasService } from '../../core/personas/personas.service';
import { PersonaSummary } from '../../core/personas/persona.models';
import { Experiments } from './experiments';

describe('Experiments', () => {
  let component: Experiments;
  let fixture: ComponentFixture<Experiments>;
  let experimentsService: Pick<
    ExperimentsService,
    'list' | 'create' | 'activate' | 'pause' | 'resume' | 'complete' | 'cancel' | 'assignments' | 'outcomes' | 'analysis'
  >;
  let personasService: Pick<PersonasService, 'list'>;

  beforeEach(async () => {
    experimentsService = {
      list: vi.fn().mockReturnValue(of([])),
      create: vi.fn().mockReturnValue(of(experiment())),
      activate: vi.fn().mockReturnValue(of(experiment({ status: 'ACTIVE' }))),
      pause: vi.fn().mockReturnValue(of(experiment({ status: 'PAUSED' }))),
      resume: vi.fn().mockReturnValue(of(experiment({ status: 'ACTIVE' }))),
      complete: vi.fn().mockReturnValue(of(experiment({ status: 'COMPLETED' }))),
      cancel: vi.fn().mockReturnValue(of(experiment({ status: 'CANCELLED' }))),
      assignments: vi.fn().mockReturnValue(of([])),
      outcomes: vi.fn().mockReturnValue(of(outcome())),
      analysis: vi.fn().mockReturnValue(of(analysisResponse())),
    };
    personasService = {
      list: vi.fn().mockReturnValue(of([activePersona('persona-1', 'Friendly'), activePersona('persona-2', 'Bold')])),
    };

    await TestBed.configureTestingModule({
      imports: [Experiments],
      providers: [
        { provide: ExperimentsService, useValue: experimentsService },
        { provide: PersonasService, useValue: personasService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('shows an empty state when there are no experiments', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No experiments yet.');
  });

  it('shows an error state when loading fails', () => {
    vi.mocked(experimentsService.list).mockReturnValue(throwError(() => new Error('boom')));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Experiments could not be loaded.');
  });

  it('always shows the descriptive-only disclaimer', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Statistical analysis describes observed outcomes and uncertainty.');
  });

  it('renders an experiment with its frozen variants and assigned counts', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Persona A/B test');
    expect(text).toContain('Draft');
    expect(text).toContain('n=5');
    expect(text).toContain('n=4');
  });

  it('never declares a winner/loser/best/worst variant anywhere in the rendered page (the disclaimer itself may use the word "winner" only to deny one)', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    const text = (fixture.nativeElement.textContent as string).toLowerCase();
    expect(text).not.toContain('variant a wins');
    expect(text).not.toContain('variant b wins');
    expect(text).not.toContain('best variant');
    expect(text).not.toContain('worst variant');
    expect(text).not.toContain('recommended variant');
    expect(text).not.toContain('loser');
    // The disclaimer legitimately contains "winner" — only to state that none is calculated.
    expect(text).toContain('no statistical winner');
  });

  it('creates an experiment with two distinct variant Personas', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['updateCreateField']('name', 'Bold vs Friendly');
    component['updateCreateField']('hypothesis', 'Bolder tone drives more saves');
    component['updateCreateField']('variantAPersonaId', 'persona-1');
    component['updateCreateField']('variantBPersonaId', 'persona-2');

    component['createExperiment']();

    expect(experimentsService.create).toHaveBeenCalledWith(
      expect.objectContaining({ variantAPersonaId: 'persona-1', variantBPersonaId: 'persona-2', factor: 'PERSONA' }),
    );
    expect(component['experiments']()).toHaveLength(1);
    expect(component['showCreateForm']()).toBe(false);
  });

  it('rejects creating an experiment when both variants use the same Persona', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['updateCreateField']('name', 'Bad experiment');
    component['updateCreateField']('hypothesis', 'hypothesis');
    component['updateCreateField']('variantAPersonaId', 'persona-1');
    component['updateCreateField']('variantBPersonaId', 'persona-1');

    component['createExperiment']();

    expect(experimentsService.create).not.toHaveBeenCalled();
    expect(component['createError']()).toContain('distinct Personas');
  });

  it('rejects creating an experiment without a hypothesis', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['updateCreateField']('name', 'Missing hypothesis');
    component['updateCreateField']('variantAPersonaId', 'persona-1');
    component['updateCreateField']('variantBPersonaId', 'persona-2');

    component['createExperiment']();

    expect(experimentsService.create).not.toHaveBeenCalled();
    expect(component['createError']()).toContain('hypothesis');
  });

  it('activates a DRAFT experiment and shows lifecycle controls change with status', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const draft = component['experiments']()[0];

    component['activate'](draft);

    expect(experimentsService.activate).toHaveBeenCalledWith(draft.id);
    expect(component['experiments']()[0].status).toBe('ACTIVE');
  });

  it('pauses an ACTIVE experiment and resumes it back to ACTIVE', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment({ status: 'ACTIVE' })]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const active = component['experiments']()[0];

    component['pause'](active);
    expect(experimentsService.pause).toHaveBeenCalledWith(active.id);
    expect(component['experiments']()[0].status).toBe('PAUSED');

    component['resume'](component['experiments']()[0]);
    expect(experimentsService.resume).toHaveBeenCalled();
    expect(component['experiments']()[0].status).toBe('ACTIVE');
  });

  it('loads assignment history and outcome evidence when a card is expanded', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const exp = component['experiments']()[0];

    component['toggleDetail'](exp);

    expect(experimentsService.assignments).toHaveBeenCalledWith(exp.id);
    expect(experimentsService.outcomes).toHaveBeenCalledWith(exp.id);
    expect(component['outcomeFor'](exp.id)).not.toBeNull();
  });

  it('shows a maturity notice from the outcome response without treating it as poor performance', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Some experiment publications have not yet reached');
  });

  // ---- Phase 14B: statistical analysis ----

  it('loads and renders the statistical analysis section with n, mean, median, SD when a card is expanded', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();

    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    expect(experimentsService.analysis).toHaveBeenCalledWith('exp-1');
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Statistical analysis');
    expect(text).toContain('Mean');
    expect(text).toContain('100.0000');
    expect(text).toContain('Median');
    expect(text).toContain('Std. deviation');
  });

  it('defaults to the assigned-observed population and toggles to per-protocol observed', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const exp = component['experiments']()[0];
    component['toggleDetail'](exp);
    fixture.detectChanges();

    expect(component['selectedPopulationFor'](exp.id)).toBe('ASSIGNED_OBSERVED');

    component['setPopulation'](exp.id, 'PER_PROTOCOL_OBSERVED');
    fixture.detectChanges();

    expect(component['selectedPopulationFor'](exp.id)).toBe('PER_PROTOCOL_OBSERVED');
    expect(component['activePopulationAnalysis'](exp.id)?.population).toBe('PER_PROTOCOL_OBSERVED');
  });

  it('presents Variant A and Variant B with identical structure — no per-side color or winner styling', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    const cards = fixture.nativeElement.querySelectorAll('.experiments__outcome-card');
    expect(cards.length).toBeGreaterThanOrEqual(2);
    const html = fixture.nativeElement.innerHTML as string;
    expect(html).not.toContain('winner-badge');
    expect(html).not.toContain('trophy');
  });

  it('shows a small-sample message and no CI/p-value when INSUFFICIENT_SAMPLE', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    vi.mocked(experimentsService.analysis).mockReturnValue(
      of(analysisResponse({ assignedObserved: population('ASSIGNED_OBSERVED', 'INSUFFICIENT_SAMPLE') })),
    );
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('More observed outcomes are required for the configured inferential analysis');
    expect(text).not.toContain('Two-sided Welch p-value');
  });

  it('shows full Welch evidence (SE/df/CI/p-value/effect size) when READY', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('95% CI');
    expect(text).toContain('-25.0000');
    expect(text).toContain('5.0000');
    expect(text).toContain('Two-sided Welch p-value: 0.180000');
    expect(text).toContain("Hedges' g");
    expect(text).toContain('-0.8500');
  });

  it('explains a confidence interval that includes zero without declaring a result', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('remain compatible with effects in either direction');
  });

  it('explains a confidence interval that excludes zero without declaring a winner', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    vi.mocked(experimentsService.analysis).mockReturnValue(
      of(
        analysisResponse({
          assignedObserved: population('ASSIGNED_OBSERVED', 'READY', { confidenceIntervalIncludesZero: false }),
        }),
      ),
    );
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('The 95% interval does not include zero.');
    expect(text.toLowerCase()).not.toContain('wins');
  });

  it('shows the active-experiment interim-analysis warning only when the experiment is ACTIVE', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment({ status: 'ACTIVE' })]));
    vi.mocked(experimentsService.analysis).mockReturnValue(
      of(analysisResponse({ experimentStatus: 'ACTIVE', activeExperimentWarning: 'This experiment is still assigning runs. Repeatedly checking interim results and stopping based on them can bias inference.' })),
    );
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Repeatedly checking interim results and stopping based on them can bias inference.');
  });

  it('shows attrition and protocol-deviation counts per variant', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    vi.mocked(experimentsService.analysis).mockReturnValue(
      of(
        analysisResponse({
          assignedObserved: {
            ...population('ASSIGNED_OBSERVED', 'READY'),
            variantA: variantAnalysis({ tooYoungCount: 3, protocolDeviationCount: 2 }),
          },
        }),
      ),
    );
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Too young');
    expect(text).toContain('Protocol deviations');
  });

  it('shows the TEST-analytics limitation when present in the response', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    vi.mocked(experimentsService.analysis).mockReturnValue(
      of(analysisResponse({ limitations: ['TEST analytics are deterministic development data and do not represent real audience behavior.'] })),
    );
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('TEST analytics are deterministic development data');
  });

  it('shows a mixed-provider notice and withholds inferential evidence', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    vi.mocked(experimentsService.analysis).mockReturnValue(
      of(analysisResponse({ assignedObserved: population('ASSIGNED_OBSERVED', 'MIXED_PROVIDERS') })),
    );
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('span more than one analytics provider');
    expect(text).not.toContain('Two-sided Welch p-value');
  });

  it('leaves the analysis unset (loading/error state) when the analysis request fails', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    vi.mocked(experimentsService.analysis).mockReturnValue(throwError(() => new Error('boom')));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    expect(component['analysisFor']('exp-1')).toBeNull();
  });

  it('never declares a winner/loser/deploy decision anywhere in the statistical analysis section (the disclaimer may say "winner" only to deny one)', () => {
    vi.mocked(experimentsService.list).mockReturnValue(of([experiment()]));
    fixture = TestBed.createComponent(Experiments);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component['toggleDetail'](component['experiments']()[0]);
    fixture.detectChanges();

    const text = (fixture.nativeElement.textContent as string).toLowerCase();
    expect(text).not.toContain('variant a wins');
    expect(text).not.toContain('variant b wins');
    expect(text).not.toContain('loser');
    expect(text).not.toContain('deploy a');
    expect(text).not.toContain('deploy b');
    expect(text).not.toContain('recommended variant');
    expect(text).toContain('does not automatically identify a variant to deploy');
  });

  function experiment(overrides: Partial<ExperimentSummary> = {}): ExperimentSummary {
    return {
      id: 'exp-1',
      name: 'Persona A/B test',
      description: null,
      hypothesis: 'Bolder tone drives more saves',
      factor: 'PERSONA',
      status: 'DRAFT',
      assignmentStrategy: 'DETERMINISTIC_BALANCED_V1',
      targetObservationWindow: 'H72',
      primaryMetric: 'VIEWS',
      variants: [
        { id: 'variant-a', variantKey: 'A', label: 'Friendly', personaId: 'persona-1', personaNameSnapshot: 'Friendly', frozen: false, assignedCount: 5 },
        { id: 'variant-b', variantKey: 'B', label: 'Bold', personaId: 'persona-2', personaNameSnapshot: 'Bold', frozen: false, assignedCount: 4 },
      ],
      createdAt: '2026-09-20T08:00:00Z',
      updatedAt: '2026-09-20T08:00:00Z',
      activatedAt: null,
      stoppedAt: null,
      ...overrides,
    };
  }

  function outcome(): ExperimentOutcome {
    return {
      experimentId: 'exp-1',
      targetObservationWindow: 'H72',
      primaryMetric: 'VIEWS',
      disclaimer: 'Experiment outcomes are descriptive in Phase 14A. No statistical winner or causal conclusion is calculated yet.',
      notices: ['Some experiment publications have not yet reached the configured observation window.'],
      variants: [
        {
          variantId: 'variant-a', variantKey: 'A', label: 'Friendly', assignedRuns: 5, failedRuns: 0, runsWithDraft: 5,
          publishedCount: 4, eligibleByAgeCount: 3, analyticsPublicationCount: 3, metricSampleCount: 3,
          coverage: '1.0000', average: '100', median: '95',
        },
        {
          variantId: 'variant-b', variantKey: 'B', label: 'Bold', assignedRuns: 4, failedRuns: 1, runsWithDraft: 3,
          publishedCount: 3, eligibleByAgeCount: 2, analyticsPublicationCount: 2, metricSampleCount: 2,
          coverage: '1.0000', average: '110', median: '105',
        },
      ],
    };
  }

  function variantAnalysis(overrides: Partial<ExperimentPopulationAnalysis['variantA']> = {}) {
    return {
      variantKey: 'A' as const,
      label: 'Friendly',
      assignmentCount: 8,
      failedRunCount: 0,
      publishedCount: 6,
      eligibleByAgeCount: 6,
      tooYoungCount: 1,
      snapshotCount: 6,
      metricSampleCount: 6,
      protocolDeviationCount: 0,
      assignmentOutcomeCoverage: '0.7500',
      eligibleOutcomeCoverage: '1.0000',
      mean: '100.0000',
      median: '95.0000',
      standardDeviation: '12.0000',
      min: '80.0000',
      max: '120.0000',
      ...overrides,
    };
  }

  function population(
    populationName: 'ASSIGNED_OBSERVED' | 'PER_PROTOCOL_OBSERVED',
    status: AnalysisStatus,
    overrides: Partial<ExperimentPopulationAnalysis['effect']> = {},
  ): ExperimentPopulationAnalysis {
    return {
      population: populationName,
      status,
      variantA: variantAnalysis(),
      variantB: variantAnalysis({ variantKey: 'B', label: 'Bold', mean: '110.0000', median: '105.0000', standardDeviation: '10.0000' }),
      effect: {
        absoluteMeanDifference: status === 'NO_OBSERVATIONS' ? null : '-10.0000',
        relativeMeanDifferencePercent: status === 'NO_OBSERVATIONS' ? null : '-9.0909',
        standardError: status === 'READY' ? '6.5000' : null,
        degreesOfFreedom: status === 'READY' ? '9.8000' : null,
        confidenceIntervalLower: status === 'READY' ? '-25.0000' : null,
        confidenceIntervalUpper: status === 'READY' ? '5.0000' : null,
        confidenceIntervalIncludesZero: status === 'READY' ? true : null,
        pValue: status === 'READY' ? '0.180000' : null,
        standardizedEffectSize: status === 'READY' ? '-0.8500' : null,
        ...overrides,
      },
    };
  }

  function analysisResponse(overrides: Partial<ExperimentAnalysisResponse> = {}): ExperimentAnalysisResponse {
    return {
      analysisVersion: 'EXPERIMENT_ANALYSIS_V1',
      experimentId: 'exp-1',
      experimentName: 'Persona A/B test',
      experimentStatus: 'DRAFT',
      factor: 'PERSONA',
      targetObservationWindow: 'H72',
      primaryMetric: 'VIEWS',
      confidenceLevel: '0.95',
      activeExperimentWarning: null,
      limitations: [
        'This analysis describes the observed association between assigned variants in this sample and does not automatically identify a variant to deploy.',
        'Variant A has 6 observed outcome(s) and Variant B has 6 observed outcome(s) in the assigned-observed population.',
      ],
      assignedObserved: population('ASSIGNED_OBSERVED', 'READY'),
      perProtocolObserved: population('PER_PROTOCOL_OBSERVED', 'READY'),
      ...overrides,
    };
  }

  function activePersona(id: string, name: string): PersonaSummary {
    return {
      id,
      name,
      description: null,
      status: 'ACTIVE',
      defaultLanguage: 'AUTO',
      defaultTone: 'NEUTRAL',
      audience: null,
      voiceDescription: 'A voice.',
      styleGuidelines: null,
      avoidGuidelines: null,
      hashtagGuidelines: null,
      exampleCopy: null,
      createdAt: '2026-09-18T07:00:00Z',
      updatedAt: '2026-09-18T07:00:00Z',
    };
  }
});
