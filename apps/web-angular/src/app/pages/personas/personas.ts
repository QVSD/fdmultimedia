import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EMPTY, Subscription, catchError, finalize, interval, startWith, switchMap } from 'rxjs';

import { PersonasService } from '../../core/personas/personas.service';
import { CreatePersonaRequest, PersonaSummary } from '../../core/personas/persona.models';

type LoadState = 'loading' | 'ready' | 'error';

interface PersonaFormState {
  name: string;
  description: string;
  defaultLanguage: PersonaSummary['defaultLanguage'];
  defaultTone: PersonaSummary['defaultTone'];
  audience: string;
  voiceDescription: string;
  styleGuidelines: string;
  avoidGuidelines: string;
  hashtagGuidelines: string;
  exampleCopy: string;
}

function emptyForm(): PersonaFormState {
  return {
    name: '',
    description: '',
    defaultLanguage: 'AUTO',
    defaultTone: 'NEUTRAL',
    audience: '',
    voiceDescription: '',
    styleGuidelines: '',
    avoidGuidelines: '',
    hashtagGuidelines: '',
    exampleCopy: '',
  };
}

@Component({
  selector: 'app-personas',
  imports: [DatePipe, FormsModule],
  templateUrl: './personas.html',
  styleUrl: './personas.scss',
})
export class Personas implements OnInit, OnDestroy {
  protected readonly personas = signal<PersonaSummary[]>([]);
  protected readonly loadState = signal<LoadState>('loading');

  protected readonly showCreateForm = signal(false);
  protected readonly createForm = signal<PersonaFormState>(emptyForm());
  protected readonly createBusy = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly editingPersonaId = signal<string | null>(null);
  protected readonly editForm = signal<PersonaFormState>(emptyForm());
  protected readonly editBusy = signal<Record<string, boolean>>({});
  protected readonly editErrors = signal<Record<string, string | null>>({});

  protected readonly archiveBusy = signal<Record<string, boolean>>({});

  protected readonly maxLengths = {
    name: 100,
    description: 500,
    audience: 500,
    voiceDescription: 1000,
    styleGuidelines: 2000,
    avoidGuidelines: 2000,
    hashtagGuidelines: 1000,
    exampleCopy: 2000,
  };

  private subscription?: Subscription;

  constructor(private readonly personasService: PersonasService) {}

  ngOnInit(): void {
    this.subscription = interval(5000)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.personasService.list().pipe(
            catchError(() => {
              this.loadState.set('error');
              return EMPTY;
            }),
          ),
        ),
      )
      .subscribe((personas) => {
        this.personas.set(personas);
        this.loadState.set('ready');
      });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  protected activePersonas(): PersonaSummary[] {
    return this.personas().filter((persona) => persona.status === 'ACTIVE');
  }

  protected archivedPersonas(): PersonaSummary[] {
    return this.personas().filter((persona) => persona.status === 'ARCHIVED');
  }

  // ---- create ----

  protected toggleCreateForm(): void {
    this.showCreateForm.update((value) => !value);
    this.createError.set(null);
    if (this.showCreateForm()) {
      this.createForm.set(emptyForm());
    }
  }

  protected updateCreateField<K extends keyof PersonaFormState>(field: K, value: PersonaFormState[K]): void {
    this.createForm.update((form) => ({ ...form, [field]: value }));
  }

  protected createPersona(): void {
    this.createError.set(null);
    const form = this.createForm();
    const name = form.name.trim();
    const voiceDescription = form.voiceDescription.trim();
    if (!name) {
      this.createError.set('Name is required.');
      return;
    }
    if (!voiceDescription) {
      this.createError.set('Voice description is required.');
      return;
    }
    this.createBusy.set(true);
    this.personasService
      .create(this.toRequest(form))
      .pipe(finalize(() => this.createBusy.set(false)))
      .subscribe({
        next: (persona) => {
          this.personas.set([persona, ...this.personas()]);
          this.showCreateForm.set(false);
          this.createForm.set(emptyForm());
        },
        error: () => this.createError.set('Persona could not be created.'),
      });
  }

  // ---- edit ----

  protected isEditing(persona: PersonaSummary): boolean {
    return this.editingPersonaId() === persona.id;
  }

  protected startEditing(persona: PersonaSummary): void {
    this.editingPersonaId.set(persona.id);
    this.editForm.set({
      name: persona.name,
      description: persona.description ?? '',
      defaultLanguage: persona.defaultLanguage,
      defaultTone: persona.defaultTone,
      audience: persona.audience ?? '',
      voiceDescription: persona.voiceDescription,
      styleGuidelines: persona.styleGuidelines ?? '',
      avoidGuidelines: persona.avoidGuidelines ?? '',
      hashtagGuidelines: persona.hashtagGuidelines ?? '',
      exampleCopy: persona.exampleCopy ?? '',
    });
    this.editErrors.update((errors) => ({ ...errors, [persona.id]: null }));
  }

  protected cancelEditing(): void {
    this.editingPersonaId.set(null);
  }

  protected updateEditField<K extends keyof PersonaFormState>(field: K, value: PersonaFormState[K]): void {
    this.editForm.update((form) => ({ ...form, [field]: value }));
  }

  protected saveEdits(persona: PersonaSummary): void {
    const form = this.editForm();
    const name = form.name.trim();
    const voiceDescription = form.voiceDescription.trim();
    if (!name || !voiceDescription) {
      this.editErrors.update((errors) => ({ ...errors, [persona.id]: 'Name and voice description are required.' }));
      return;
    }
    this.editBusy.update((busy) => ({ ...busy, [persona.id]: true }));
    this.personasService
      .update(persona.id, this.toRequest(form))
      .pipe(finalize(() => this.editBusy.update((busy) => ({ ...busy, [persona.id]: false }))))
      .subscribe({
        next: (updated) => {
          this.replacePersona(updated);
          this.editingPersonaId.set(null);
        },
        error: () => this.editErrors.update((errors) => ({ ...errors, [persona.id]: 'Persona could not be updated.' })),
      });
  }

  // ---- archive/restore ----

  protected archive(persona: PersonaSummary): void {
    this.archiveBusy.update((busy) => ({ ...busy, [persona.id]: true }));
    this.personasService
      .archive(persona.id)
      .pipe(finalize(() => this.archiveBusy.update((busy) => ({ ...busy, [persona.id]: false }))))
      .subscribe({
        next: (updated) => this.replacePersona(updated),
        error: () => undefined,
      });
  }

  protected restore(persona: PersonaSummary): void {
    this.archiveBusy.update((busy) => ({ ...busy, [persona.id]: true }));
    this.personasService
      .restore(persona.id)
      .pipe(finalize(() => this.archiveBusy.update((busy) => ({ ...busy, [persona.id]: false }))))
      .subscribe({
        next: (updated) => this.replacePersona(updated),
        error: () => undefined,
      });
  }

  protected toneLabel(tone: PersonaSummary['defaultTone']): string {
    switch (tone) {
      case 'NEUTRAL':
        return 'Neutral';
      case 'INFORMATIVE':
        return 'Informative';
      case 'CASUAL':
        return 'Casual';
      case 'ENERGETIC':
        return 'Energetic';
    }
  }

  protected languageLabel(language: PersonaSummary['defaultLanguage']): string {
    switch (language) {
      case 'AUTO':
        return 'Auto';
      case 'ENGLISH':
        return 'English';
      case 'ROMANIAN':
        return 'Romanian';
    }
  }

  private replacePersona(persona: PersonaSummary): void {
    this.personas.set(this.personas().map((existing) => (existing.id === persona.id ? persona : existing)));
  }

  private toRequest(form: PersonaFormState): CreatePersonaRequest {
    return {
      name: form.name.trim(),
      description: form.description.trim() || null,
      defaultLanguage: form.defaultLanguage,
      defaultTone: form.defaultTone,
      audience: form.audience.trim() || null,
      voiceDescription: form.voiceDescription.trim(),
      styleGuidelines: form.styleGuidelines.trim() || null,
      avoidGuidelines: form.avoidGuidelines.trim() || null,
      hashtagGuidelines: form.hashtagGuidelines.trim() || null,
      exampleCopy: form.exampleCopy.trim() || null,
    };
  }
}
