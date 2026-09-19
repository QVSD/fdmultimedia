import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { PersonasService } from '../../core/personas/personas.service';
import { PersonaSummary } from '../../core/personas/persona.models';
import { Personas } from './personas';

describe('Personas', () => {
  let component: Personas;
  let fixture: ComponentFixture<Personas>;
  let personasService: Pick<PersonasService, 'list' | 'create' | 'update' | 'archive' | 'restore'>;

  beforeEach(async () => {
    personasService = {
      list: vi.fn().mockReturnValue(of([])),
      create: vi.fn().mockReturnValue(of(persona())),
      update: vi.fn().mockReturnValue(of(persona({ name: 'Renamed' }))),
      archive: vi.fn().mockReturnValue(of(persona({ status: 'ARCHIVED' }))),
      restore: vi.fn().mockReturnValue(of(persona({ status: 'ACTIVE' }))),
    };

    await TestBed.configureTestingModule({
      imports: [Personas],
      providers: [{ provide: PersonasService, useValue: personasService }],
    }).compileComponents();

    fixture = TestBed.createComponent(Personas);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('shows an empty state when there are no personas', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No personas yet');
  });

  it('shows an error state when loading fails', () => {
    vi.mocked(personasService.list).mockReturnValue(throwError(() => new Error('boom')));
    fixture = TestBed.createComponent(Personas);
    component = fixture.componentInstance;
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Could not load personas');
  });

  it('renders an existing active Persona with its default language/tone and safely formatted date', () => {
    vi.mocked(personasService.list).mockReturnValue(of([persona()]));
    fixture = TestBed.createComponent(Personas);
    component = fixture.componentInstance;
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Tech Romania');
    expect(text).toContain('Romanian');
    expect(text).toContain('Informative');
    expect(text).toContain('Active');
  });

  it('creates a Persona and prepends it to the list', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['updateCreateField']('name', 'Tech Romania');
    component['updateCreateField']('voiceDescription', 'Direct voice.');

    component['createPersona']();

    expect(personasService.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'Tech Romania', voiceDescription: 'Direct voice.' }));
    expect(component['personas']()).toHaveLength(1);
    expect(component['showCreateForm']()).toBe(false);
  });

  it('rejects creating a Persona without a name', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['updateCreateField']('voiceDescription', 'Direct voice.');

    component['createPersona']();

    expect(personasService.create).not.toHaveBeenCalled();
    expect(component['createError']()).toContain('Name is required');
  });

  it('rejects creating a Persona without a voice description', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['updateCreateField']('name', 'Tech Romania');

    component['createPersona']();

    expect(personasService.create).not.toHaveBeenCalled();
    expect(component['createError']()).toContain('Voice description is required');
  });

  it('enforces character limits via input maxlength attributes matching the documented bounds', () => {
    fixture.detectChanges();
    component['toggleCreateForm']();
    fixture.detectChanges();

    const nameInput = fixture.nativeElement.querySelector('input[name="create-name"]') as HTMLInputElement;
    const voiceTextarea = fixture.nativeElement.querySelector('textarea[name="create-voice"]') as HTMLTextAreaElement;
    expect(nameInput.maxLength).toBe(100);
    expect(voiceTextarea.maxLength).toBe(1000);
  });

  it('surfaces a create failure without crashing', () => {
    vi.mocked(personasService.create).mockReturnValue(throwError(() => new Error('boom')));
    fixture.detectChanges();
    component['toggleCreateForm']();
    component['updateCreateField']('name', 'Tech Romania');
    component['updateCreateField']('voiceDescription', 'Direct voice.');

    component['createPersona']();

    expect(component['createError']()).toBe('Persona could not be created.');
  });

  it('edits an existing Persona and reflects the update', () => {
    vi.mocked(personasService.list).mockReturnValue(of([persona()]));
    fixture = TestBed.createComponent(Personas);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const existing = component['personas']()[0];

    component['startEditing'](existing);
    component['updateEditField']('name', 'Renamed');
    component['saveEdits'](existing);

    expect(personasService.update).toHaveBeenCalledWith(existing.id, expect.objectContaining({ name: 'Renamed' }));
    expect(component['personas']()[0].name).toBe('Renamed');
    expect(component['editingPersonaId']()).toBeNull();
  });

  it('rejects saving edits with a blank voice description', () => {
    vi.mocked(personasService.list).mockReturnValue(of([persona()]));
    fixture = TestBed.createComponent(Personas);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const existing = component['personas']()[0];
    component['startEditing'](existing);
    component['updateEditField']('voiceDescription', '   ');

    component['saveEdits'](existing);

    expect(personasService.update).not.toHaveBeenCalled();
    expect(component['editErrors']()[existing.id]).toContain('required');
  });

  it('archives an active Persona and moves it out of the active list', () => {
    vi.mocked(personasService.list).mockReturnValue(of([persona()]));
    fixture = TestBed.createComponent(Personas);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const existing = component['personas']()[0];

    component['archive'](existing);

    expect(personasService.archive).toHaveBeenCalledWith(existing.id);
    expect(component['activePersonas']()).toHaveLength(0);
    expect(component['archivedPersonas']()).toHaveLength(1);
  });

  it('restores an archived Persona back to the active list', () => {
    vi.mocked(personasService.list).mockReturnValue(of([persona({ status: 'ARCHIVED' })]));
    fixture = TestBed.createComponent(Personas);
    component = fixture.componentInstance;
    fixture.detectChanges();
    const existing = component['personas']()[0];

    component['restore'](existing);

    expect(personasService.restore).toHaveBeenCalledWith(existing.id);
    expect(component['activePersonas']()).toHaveLength(1);
  });

  function persona(overrides: Partial<PersonaSummary> = {}): PersonaSummary {
    return {
      id: 'persona-1',
      name: 'Tech Romania',
      description: null,
      status: 'ACTIVE',
      defaultLanguage: 'ROMANIAN',
      defaultTone: 'INFORMATIVE',
      audience: 'Founders',
      voiceDescription: 'Direct and warm.',
      styleGuidelines: null,
      avoidGuidelines: null,
      hashtagGuidelines: null,
      exampleCopy: null,
      createdAt: '2026-09-19T08:00:00Z',
      updatedAt: '2026-09-19T08:00:00Z',
      ...overrides,
    };
  }
});
