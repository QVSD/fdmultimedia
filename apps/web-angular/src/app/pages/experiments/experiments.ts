import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EMPTY, Subscription, catchError, finalize, interval, startWith, switchMap } from 'rxjs';

import { ExperimentsService } from '../../core/experiments/experiments.service';
import {
  CreateExperimentRequest,
  ExperimentAssignmentSummary,
  ExperimentMetric,
  ExperimentObservationWindow,
  ExperimentOutcome,
  ExperimentSummary,
} from '../../core/experiments/experiment.models';
import { PersonasService } from '../../core/personas/personas.service';
import { PersonaSummary } from '../../core/personas/persona.models';

type LoadState = 'loading' | 'ready' | 'error';

interface ExperimentFormState {
  name: string;
  description: string;
  hypothesis: string;
  targetObservationWindow: ExperimentObservationWindow;
  primaryMetric: ExperimentMetric;
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
  protected readonly loadState = signal<LoadState>('loading');

  protected readonly windows: ExperimentObservationWindow[] = ['H24', 'H72', 'D7'];
  protected readonly metrics: ExperimentMetric[] = ['VIEWS', 'REACH', 'LIKES', 'COMMENTS', 'SHARES', 'SAVES', 'TOTAL_INTERACTIONS'];

  protected readonly showCreateForm = signal(false);
  protected readonly createForm = signal<ExperimentFormState>(emptyForm());
  protected readonly createBusy = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly expandedExperimentId = signal<string | null>(null);
  protected readonly assignmentsByExperiment = signal<Record<string, ExperimentAssignmentSummary[]>>({});
  protected readonly outcomeByExperiment = signal<Record<string, ExperimentOutcome>>({});
  protected readonly detailLoadState = signal<Record<string, LoadState>>({});
  protected readonly lifecycleBusy = signal<Record<string, boolean>>({});
  protected readonly lifecycleError = signal<Record<string, string | null>>({});

  private subscription?: Subscription;

  constructor(
    private readonly experimentsService: ExperimentsService,
    private readonly personasService: PersonasService,
  ) {}

  ngOnInit(): void {
    this.personasService.list().subscribe((personas) => this.personas.set(personas));
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
      this.createForm.set(emptyForm());
    }
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
    const request: CreateExperimentRequest = {
      name,
      description: form.description.trim() || null,
      hypothesis,
      factor: 'PERSONA',
      targetObservationWindow: form.targetObservationWindow,
      primaryMetric: form.primaryMetric,
      variantAPersonaId: form.variantAPersonaId,
      variantALabel: form.variantALabel.trim() || null,
      variantBPersonaId: form.variantBPersonaId,
      variantBLabel: form.variantBLabel.trim() || null,
    };
    this.createBusy.set(true);
    this.experimentsService
      .create(request)
      .pipe(finalize(() => this.createBusy.set(false)))
      .subscribe({
        next: (experiment) => {
          this.experiments.set([experiment, ...this.experiments()]);
          this.showCreateForm.set(false);
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
  }

  protected assignmentsFor(experimentId: string): ExperimentAssignmentSummary[] {
    return this.assignmentsByExperiment()[experimentId] ?? [];
  }

  protected outcomeFor(experimentId: string): ExperimentOutcome | null {
    return this.outcomeByExperiment()[experimentId] ?? null;
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
