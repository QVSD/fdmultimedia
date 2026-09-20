import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { ExperimentsService } from '../../core/experiments/experiments.service';
import { ExperimentOutcome, ExperimentSummary } from '../../core/experiments/experiment.models';
import { PersonasService } from '../../core/personas/personas.service';
import { PersonaSummary } from '../../core/personas/persona.models';
import { Experiments } from './experiments';

describe('Experiments', () => {
  let component: Experiments;
  let fixture: ComponentFixture<Experiments>;
  let experimentsService: Pick<
    ExperimentsService,
    'list' | 'create' | 'activate' | 'pause' | 'resume' | 'complete' | 'cancel' | 'assignments' | 'outcomes'
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

    expect(fixture.nativeElement.textContent).toContain('No statistical winner or causal conclusion is calculated yet.');
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
