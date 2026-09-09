import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, Router } from '@angular/router';
import { throwError, of } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { Login } from './login';

describe('Login', () => {
  const auth = {
    login: vi.fn(),
  };

  beforeEach(async () => {
    auth.login.mockReset();
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    }).compileComponents();
  });

  it('validates required fields', () => {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();

    expect(auth.login).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Enter a valid email address.');
    expect(fixture.nativeElement.textContent).toContain('Password is required.');
  });

  it('redirects after successful login', () => {
    const router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl');
    auth.login.mockReturnValue(of({}));
    const fixture = TestBed.createComponent(Login);
    fixture.componentInstance['form'].setValue({ email: 'owner@example.com', password: 'secret' });

    fixture.componentInstance['submit']();

    expect(auth.login).toHaveBeenCalledWith({ email: 'owner@example.com', password: 'secret' });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('shows invalid credentials state', () => {
    auth.login.mockReturnValue(throwError(() => ({ status: 401 })));
    const fixture = TestBed.createComponent(Login);
    fixture.componentInstance['form'].setValue({ email: 'owner@example.com', password: 'wrong' });

    fixture.componentInstance['submit']();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Invalid email or password.');
  });
});
