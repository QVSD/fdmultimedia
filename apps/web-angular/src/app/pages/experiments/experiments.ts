import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EMPTY, Subscription, catchError, finalize, interval, startWith, switchMap } from 'rxjs';

import { ExperimentsService } from '../../core/experiments/experiments.service';
import {
  AnalysisPopulation,
  CreateExperimentRequest,
  DecisionReadiness,
  DecisionRecord,
  DecisionType,
  DecisionApplicationPreview,
  DecisionApplicationRecord,
  ExperimentAnalysisResponse,
  ExperimentAssignmentSummary,
  ExperimentMetric,
  ExperimentObservationWindow,
  ExperimentOutcome,
  ExperimentPopulationAnalysis,
  ExperimentSummary,
} from '../../core/experiments/experiment.models';
import { PersonasService } from '../../core/personas/personas.service';
import { PersonaSummary } from '../../core/personas/persona.models';
import { RobotsService } from '../../core/robots/robots.service';
import { RobotSummary } from '../../core/robots/robot.models';

type LoadState = 'loading' | 'ready' | 'error';

interface ExperimentFormState {
  name: string;
  description: string;
  hypothesis: string;
  targetObservationWindow: ExperimentObservationWindow;
  primaryMetric: ExperimentMetric;
  minimumPracticalEffect: string;
  variantAPersonaId: string;
  variantALabel: string;
  variantBPersonaId: string;
  variantBLabel: string;
}

function emptyForm(): ExperimentFormState {
  return {
    name: '',
    description: '',
    hypothesis: '',
    targetObservationWindow: 'H72',
    primaryMetric: 'VIEWS',
    minimumPracticalEffect: '',
    variantAPersonaId: '',
    variantALabel: '',
    variantBPersonaId: '',
    variantBLabel: '',
  };
}

@Component({
  selector: 'app-experiments',
  imports: [DatePipe, FormsModule],
  templateUrl: './experiments.html',
  styleUrl: './experiments.scss',
})
export class Experiments implements OnInit, OnDestroy {
  protected readonly experiments = signal<ExperimentSummary[]>([]);
  protected readonly personas = signal<PersonaSummary[]>([]);
  protected readonly robots = signal<RobotSummary[]>([]);
  protected readonly loadState = signal<LoadState>('loading');

  protected readonly windows: ExperimentObservationWindow[] = ['H24', 'H72', 'D7'];
  protected readonly metrics: ExperimentMetric[] = ['VIEWS', 'REACH', 'LIKES', 'COMMENTS', 'SHARES', 'SAVES', 'TOTAL_INTERACTIONS'];

  protected readonly showCreateForm = signal(false);
  protected readonly editingExperimentId = signal<string | null>(null);
  protected readonly createForm = signal<ExperimentFormState>(emptyForm());
  protected readonly createBusy = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly expandedExperimentId = signal<string | null>(null);
  protected readonly assignmentsByExperiment = signal<Record<string, ExperimentAssignmentSummary[]>>({});
  protected readonly outcomeByExperiment = signal<Record<string, ExperimentOutcome>>({});
  protected readonly analysisByExperiment = signal<Record<string, ExperimentAnalysisResponse>>({});
  protected readonly analysisLoadState = signal<Record<string, LoadState>>({});
  protected readonly populationByExperiment = signal<Record<string, AnalysisPopulation>>({});
  protected readonly detailLoadState = signal<Record<string, LoadState>>({});
  protected readonly lifecycleBusy = signal<Record<string, boolean>>({});
  protected readonly lifecycleError = signal<Record<string, string | null>>({});
  protected readonly readinessByExperiment = signal<Record<string, DecisionReadiness>>({});
  protected readonly decisionsByExperiment = signal<Record<string, DecisionRecord[]>>({});
  protected readonly decisionType = signal<DecisionType>('INCONCLUSIVE');
  protected readonly decisionRationale = signal('');
  protected readonly decisionBusy = signal(false);
  protected readonly decisionError = signal<string | null>(null);
  protected readonly applicationsByExperiment = signal<Record<string, DecisionApplicationRecord[]>>({});
  protected readonly applicationPreview = signal<DecisionApplicationPreview | null>(null);
  protected readonly applicationRobotId = signal('');
  protected readonly rollbackApplicationId = signal<string | null>(null);
  protected readonly applicationBusy = signal(false);
  protected readonly applicationError = signal<string | null>(null);
  protected readonly confirmActive = signal(false);
  protected readonly confirmNotReady = signal(false);
  protected readonly confirmOlder = signal(false);
  private pendingDecisionKey: string | null = null;
  protected readonly decisionTypes: DecisionType[] = ['SELECT_VARIANT_A', 'SELECT_VARIANT_B', 'KEEP_CURRENT_CONFIGURATION', 'INCONCLUSIVE', 'CANCEL_EXPERIMENT'];

  private subscription?: Subscription;

  constructor(
    private readonly experimentsService: ExperimentsService,
    private readonly personasService: PersonasService,
    private readonly robotsService: RobotsService,
  ) {}

  ngOnInit(): void {
    this.personasService.list().subscribe((personas) => this.personas.set(personas));
    this.robotsService.list().subscribe((robots) => this.robots.set(robots));
    this.subscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.experimentsService.list().pipe(
            catchError(() => {
              this.loadState.set('error');
              return EMPTY;
            }),
          ),
        ),
      )
      .subscribe((experiments) => {
        this.experiments.set(experiments);
        this.loadState.set('ready');
      });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  protected activePersonas(): PersonaSummary[] {
    return this.personas().filter((persona) => persona.status === 'ACTIVE');
  }

  // ---- create ----

  protected toggleCreateForm(): void {
    this.showCreateForm.update((value) => !value);
    this.createError.set(null);
    if (this.showCreateForm()) {
      this.editingExperimentId.set(null);
      this.createForm.set(emptyForm());
    }
  }

  protected editDraft(experiment: ExperimentSummary): void {
    if (experiment.status !== 'DRAFT') return;
    this.editingExperimentId.set(experiment.id);
    this.showCreateForm.set(true);
    this.createError.set(null);
    this.createForm.set({
      name: experiment.name, description: experiment.description ?? '', hypothesis: experiment.hypothesis,
      targetObservationWindow: experiment.targetObservationWindow, primaryMetric: experiment.primaryMetric,
      minimumPracticalEffect: experiment.minimumPracticalEffect ?? '',
      variantAPersonaId: experiment.variants.find((v) => v.variantKey === 'A')?.personaId ?? '',
      variantALabel: experiment.variants.find((v) => v.variantKey === 'A')?.label ?? '',
      variantBPersonaId: experiment.variants.find((v) => v.variantKey === 'B')?.personaId ?? '',
      variantBLabel: experiment.variants.find((v) => v.variantKey === 'B')?.label ?? '',
    });
  }

  protected updateCreateField<K extends keyof ExperimentFormState>(field: K, value: ExperimentFormState[K]): void {
    this.createForm.update((form) => ({ ...form, [field]: value }));
  }

  protected createExperiment(): void {
    this.createError.set(null);
    const form = this.createForm();
    const name = form.name.trim();
    const hypothesis = form.hypothesis.trim();
    if (!name || !hypothesis) {
      this.createError.set('Name and hypothesis are required.');
      return;
    }
    if (!form.variantAPersonaId || !form.variantBPersonaId) {
      this.createError.set('Both Variant A and Variant B require a Persona.');
      return;
    }
    if (form.variantAPersonaId === form.variantBPersonaId) {
      this.createError.set('Variant A and Variant B must use distinct Personas.');
      return;
    }
    if (!this.validThreshold(form.minimumPracticalEffect)) {
      this.createError.set('Minimum practical effect must be positive with at most four decimal places.');
      return;
    }
    const request: CreateExperimentRequest = {
      name,
      description: form.description.trim() || null,
      hypothesis,
      factor: 'PERSONA',
      targetObservationWindow: form.targetObservationWindow,
      primaryMetric: form.primaryMetric,
      minimumPracticalEffect: form.minimumPracticalEffect,
      variantAPersonaId: form.variantAPersonaId,
      variantALabel: form.variantALabel.trim() || null,
      variantBPersonaId: form.variantBPersonaId,
      variantBLabel: form.variantBLabel.trim() || null,
    };
    this.createBusy.set(true);
    const editingId = this.editingExperimentId();
    const operation = editingId ? this.experimentsService.update(editingId, request) : this.experimentsService.create(request);
    operation
      .pipe(finalize(() => this.createBusy.set(false)))
      .subscribe({
        next: (experiment) => {
          this.experiments.set(editingId ? this.experiments().map((item) => item.id === experiment.id ? experiment : item)
            : [experiment, ...this.experiments()]);
          this.showCreateForm.set(false);
          this.editingExperimentId.set(null);
          this.createForm.set(emptyForm());
        },
        error: () => this.createError.set('Experiment could not be created.'),
      });
  }

  // ---- detail / lifecycle ----

  protected isExpanded(experiment: ExperimentSummary): boolean {
    return this.expandedExperimentId() === experiment.id;
  }

  protected toggleDetail(experiment: ExperimentSummary): void {
    if (this.isExpanded(experiment)) {
      this.expandedExperimentId.set(null);
      return;
    }
    this.expandedExperimentId.set(experiment.id);
    this.loadDetail(experiment.id);
  }

  private loadDetail(experimentId: string): void {
    this.detailLoadState.update((state) => ({ ...state, [experimentId]: 'loading' }));
    this.experimentsService.assignments(experimentId).subscribe({
      next: (assignments) => this.assignmentsByExperiment.update((byId) => ({ ...byId, [experimentId]: assignments })),
      error: () => undefined,
    });
    this.experimentsService.outcomes(experimentId).subscribe({
      next: (outcome) => {
        this.outcomeByExperiment.update((byId) => ({ ...byId, [experimentId]: outcome }));
        this.detailLoadState.update((state) => ({ ...state, [experimentId]: 'ready' }));
      },
      error: () => this.detailLoadState.update((state) => ({ ...state, [experimentId]: 'error' })),
    });
    this.analysisLoadState.update((state) => ({ ...state, [experimentId]: 'loading' }));
    if (!this.populationByExperiment()[experimentId]) {
      this.populationByExperiment.update((byId) => ({ ...byId, [experimentId]: 'ASSIGNED_OBSERVED' }));
    }
    this.experimentsService.analysis(experimentId).subscribe({
      next: (analysis) => {
        this.analysisByExperiment.update((byId) => ({ ...byId, [experimentId]: analysis }));
        this.analysisLoadState.update((state) => ({ ...state, [experimentId]: 'ready' }));
      },
      error: () => this.analysisLoadState.update((state) => ({ ...state, [experimentId]: 'error' })),
    });
    this.experimentsService.readiness(experimentId).subscribe({
      next: (value) => this.readinessByExperiment.update((all) => ({ ...all, [experimentId]: value })),
      error: () => this.decisionError.set('Decision readiness could not be loaded.'),
    });
    this.experimentsService.decisions(experimentId).subscribe({
      next: (value) => this.decisionsByExperiment.update((all) => ({ ...all, [experimentId]: value })),
      error: () => this.decisionError.set('Decision history could not be loaded.'),
    });
    this.experimentsService.applications(experimentId).subscribe({
      next: (value) => this.applicationsByExperiment.update((all) => ({ ...all, [experimentId]: value })),
      error: () => this.applicationError.set('Application history could not be loaded.'),
    });
  }

  protected linkedRobots(experimentId: string): RobotSummary[] { return this.robots().filter((robot) => robot.experimentId === experimentId); }
  protected applicationsFor(experimentId: string): DecisionApplicationRecord[] { return this.applicationsByExperiment()[experimentId] ?? []; }
  protected canRollback(application: DecisionApplicationRecord, experimentId: string): boolean {
    return application.status === 'APPLIED' && !application.noOp
      && !this.applicationsFor(experimentId).some((item) => item.rollbackOfApplicationId === application.id);
  }

  protected previewApplication(experiment: ExperimentSummary, decision: DecisionRecord): void {
    if (!this.applicationRobotId()) { this.applicationError.set('Select a Robot linked to this experiment.'); return; }
    this.applicationBusy.set(true); this.applicationError.set(null); this.rollbackApplicationId.set(null);
    this.experimentsService.applicationPreview(experiment.id, decision.id, this.applicationRobotId())
      .pipe(finalize(() => this.applicationBusy.set(false))).subscribe({
        next: (value) => { this.applicationPreview.set(value); this.confirmActive.set(false); this.confirmNotReady.set(false); this.confirmOlder.set(false); },
        error: () => this.applicationError.set('Application preview could not be loaded.'),
      });
  }

  protected applySelectedPersona(preview: DecisionApplicationPreview): void {
    this.applicationBusy.set(true); this.applicationError.set(null);
    this.experimentsService.applyDecision(preview.experimentId, preview.decisionId, {
      robotId: preview.robotId, previewFingerprint: preview.previewFingerprint, idempotencyKey: crypto.randomUUID(),
      confirmActiveExperiment: this.confirmActive(), confirmNotReadyDecision: this.confirmNotReady(), confirmOlderDecision: this.confirmOlder(),
    }).pipe(finalize(() => this.applicationBusy.set(false))).subscribe({
      next: (saved) => { this.applicationsByExperiment.update((all) => ({ ...all, [preview.experimentId]: [saved, ...(all[preview.experimentId] ?? [])] })); this.applicationPreview.set(null); this.robotsService.list().subscribe((items) => this.robots.set(items)); },
      error: (error) => this.applicationError.set(error?.error?.message?.includes('STALE') ? 'Application preview is stale. Preview again.' : 'Persona application was rejected. Review confirmations and blocking reasons.'),
    });
  }

  protected previewRollback(application: DecisionApplicationRecord): void {
    this.applicationBusy.set(true); this.applicationError.set(null); this.rollbackApplicationId.set(application.id);
    this.experimentsService.rollbackPreview(application.id).pipe(finalize(() => this.applicationBusy.set(false))).subscribe({
      next: (value) => this.applicationPreview.set(value), error: () => this.applicationError.set('Rollback preview could not be loaded.'),
    });
  }

  protected rollbackPersona(preview: DecisionApplicationPreview): void {
    const applicationId = this.rollbackApplicationId();
    if (!applicationId || !confirm('Rollback changes only the Robot Persona if the Robot still matches the state created by this application. Continue?')) return;
    this.applicationBusy.set(true); this.applicationError.set(null);
    this.experimentsService.rollback(applicationId, preview.previewFingerprint, crypto.randomUUID())
      .pipe(finalize(() => this.applicationBusy.set(false))).subscribe({
        next: (saved) => { this.applicationsByExperiment.update((all) => ({ ...all, [saved.experimentId]: [saved, ...(all[saved.experimentId] ?? [])] })); this.applicationPreview.set(null); this.rollbackApplicationId.set(null); this.robotsService.list().subscribe((items) => this.robots.set(items)); },
        error: () => this.applicationError.set('Rollback was rejected because the preview is stale or Robot state diverged.'),
      });
  }

  private validThreshold(value: string): boolean {
    return /^\d{1,16}(?:\.\d{1,4})?$/.test(value) && Number(value) > 0;
  }

  protected readinessFor(id: string): DecisionReadiness | null {
    return this.readinessByExperiment()[id] ?? null;
  }

  protected decisionPopulation(id: string): DecisionReadiness['assignedObserved'] | null {
    const value = this.readinessFor(id);
    return value ? this.selectedPopulationFor(id) === 'ASSIGNED_OBSERVED' ? value.assignedObserved : value.perProtocolObserved : null;
  }

  protected recordDecision(experiment: ExperimentSummary): void {
    const rationale = this.decisionRationale();
    if (!rationale.trim() || rationale.length > 2000) {
      this.decisionError.set('Rationale must contain 1 to 2000 characters.');
      return;
    }
    if (!this.readinessFor(experiment.id) || this.decisionBusy()) return;
    const type = this.decisionType();
    const population = this.selectedPopulationFor(experiment.id);
    if (experiment.status === 'ACTIVE' && !confirm('This experiment is still assigning runs. Recording a decision does not pause or complete it. Continue?')) return;
    if (this.decisionPopulation(experiment.id)?.readinessStatus === 'NOT_READY'
        && !confirm('Evidence guardrails are not ready. This state will be frozen in the audit record. Continue?')) return;
    this.pendingDecisionKey ??= crypto.randomUUID();
    this.decisionBusy.set(true);
    this.decisionError.set(null);
    this.experimentsService.recordDecision(experiment.id, type, population, rationale, this.pendingDecisionKey)
      .pipe(finalize(() => this.decisionBusy.set(false)))
      .subscribe({
        next: (saved) => {
          this.pendingDecisionKey = null;
          this.decisionRationale.set('');
          this.decisionsByExperiment.update((all) => ({ ...all, [experiment.id]: [saved, ...(all[experiment.id] ?? []).filter((d) => d.id !== saved.id)] }));
        },
        error: () => this.decisionError.set('Decision could not be recorded. Retry uses the same submission key.'),
      });
  }

  protected assignmentsFor(experimentId: string): ExperimentAssignmentSummary[] {
    return this.assignmentsByExperiment()[experimentId] ?? [];
  }

  protected outcomeFor(experimentId: string): ExperimentOutcome | null {
    return this.outcomeByExperiment()[experimentId] ?? null;
  }

  protected analysisFor(experimentId: string): ExperimentAnalysisResponse | null {
    return this.analysisByExperiment()[experimentId] ?? null;
  }

  protected selectedPopulationFor(experimentId: string): AnalysisPopulation {
    return this.populationByExperiment()[experimentId] ?? 'ASSIGNED_OBSERVED';
  }

  protected setPopulation(experimentId: string, population: AnalysisPopulation): void {
    this.populationByExperiment.update((byId) => ({ ...byId, [experimentId]: population }));
  }

  protected activePopulationAnalysis(experimentId: string): ExperimentPopulationAnalysis | null {
    const analysis = this.analysisFor(experimentId);
    if (!analysis) {
      return null;
    }
    return this.selectedPopulationFor(experimentId) === 'ASSIGNED_OBSERVED' ? analysis.assignedObserved : analysis.perProtocolObserved;
  }

  protected analysisStatusLabel(status: ExperimentAnalysisResponse['assignedObserved']['status']): string {
    switch (status) {
      case 'NO_OBSERVATIONS':
        return 'No observations yet';
      case 'MIXED_PROVIDERS':
        return 'Mixed providers — inference not performed';
      case 'INSUFFICIENT_SAMPLE':
        return 'Insufficient sample';
      case 'INSUFFICIENT_VARIANCE':
        return 'Insufficient variance';
      case 'READY':
        return 'Ready';
    }
  }

  protected populationLabel(population: AnalysisPopulation): string {
    return population === 'ASSIGNED_OBSERVED' ? 'Assigned observed' : 'Per-protocol observed';
  }

  protected confidenceIntervalExplanation(population: ExperimentPopulationAnalysis): string {
    if (population.status !== 'READY') {
      return '';
    }
    return population.effect.confidenceIntervalIncludesZero
      ? 'The observed data remain compatible with effects in either direction at this confidence level.'
      : 'The 95% interval does not include zero.';
  }

  protected assignedCount(variantKey: 'A' | 'B', experiment: ExperimentSummary): number {
    return experiment.variants.find((variant) => variant.variantKey === variantKey)?.assignedCount ?? 0;
  }

  protected activate(experiment: ExperimentSummary): void {
    this.runLifecycleAction(experiment, () => this.experimentsService.activate(experiment.id));
  }

  protected pause(experiment: ExperimentSummary): void {
    this.runLifecycleAction(experiment, () => this.experimentsService.pause(experiment.id));
  }

  protected resume(experiment: ExperimentSummary): void {
    this.runLifecycleAction(experiment, () => this.experimentsService.resume(experiment.id));
  }

  protected complete(experiment: ExperimentSummary): void {
    if (!confirm(`Complete "${experiment.name}"? This is terminal and cannot be undone.`)) {
      return;
    }
    this.runLifecycleAction(experiment, () => this.experimentsService.complete(experiment.id));
  }

  protected cancel(experiment: ExperimentSummary): void {
    if (!confirm(`Cancel "${experiment.name}"? This is terminal and cannot be undone.`)) {
      return;
    }
    this.runLifecycleAction(experiment, () => this.experimentsService.cancel(experiment.id));
  }

  private runLifecycleAction(experiment: ExperimentSummary, action: () => import('rxjs').Observable<ExperimentSummary>): void {
    this.lifecycleBusy.update((busy) => ({ ...busy, [experiment.id]: true }));
    this.lifecycleError.update((errors) => ({ ...errors, [experiment.id]: null }));
    action()
      .pipe(finalize(() => this.lifecycleBusy.update((busy) => ({ ...busy, [experiment.id]: false }))))
      .subscribe({
        next: (updated) => this.experiments.set(this.experiments().map((e) => (e.id === updated.id ? updated : e))),
        error: () => this.lifecycleError.update((errors) => ({ ...errors, [experiment.id]: 'Action failed.' })),
      });
  }

  protected statusLabel(status: ExperimentSummary['status']): string {
    switch (status) {
      case 'DRAFT':
        return 'Draft';
      case 'ACTIVE':
        return 'Active';
      case 'PAUSED':
        return 'Paused';
      case 'COMPLETED':
        return 'Completed';
      case 'CANCELLED':
        return 'Cancelled';
    }
  }

  protected windowLabel(window: ExperimentObservationWindow): string {
    switch (window) {
      case 'H24':
        return '24 hours';
      case 'H72':
        return '72 hours';
      case 'D7':
        return '7 days';
    }
  }
}
