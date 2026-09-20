import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { App } from './app';
import { AuthService } from './core/auth/auth.service';
import { routes } from './app.routes';

describe('App', () => {
  const currentUser = signal({
    id: 'user-id',
    email: 'owner@example.com',
    displayName: 'Owner',
  });
  const currentWorkspace = signal({
    id: 'workspace-id',
    name: 'FD Multimedia',
    slug: 'fd-multimedia',
    role: 'OWNER' as const,
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render the sidebar navigation', async () => {
    TestBed.overrideProvider(AuthService, {
      useValue: {
        currentUser,
        currentWorkspace,
        logout: () => of(undefined),
      },
    });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.sidebar__brand')?.textContent).toContain('FD Multimedia');
    expect(compiled.querySelectorAll('.sidebar__link').length).toBe(8);
    expect(compiled.textContent).toContain('Overview');
    expect(compiled.textContent).toContain('Content');
    expect(compiled.textContent).toContain('Robots');
    expect(compiled.textContent).toContain('Personas');
    expect(compiled.textContent).toContain('Experiments');
    expect(compiled.textContent).toContain('Compute');
    expect(compiled.textContent).toContain('Jobs');
    expect(compiled.textContent).toContain('Settings');
    expect(compiled.textContent).not.toContain('Analytics');
    expect(compiled.textContent).not.toContain('Revenue');
    expect(compiled.textContent).toContain('Owner');
    expect(compiled.textContent).toContain('FD Multimedia');
    expect(compiled.textContent).toContain('OWNER');
  });
});
