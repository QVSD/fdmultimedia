import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Overview } from './overview';

describe('Overview', () => {
  let component: Overview;
  let fixture: ComponentFixture<Overview>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Overview],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(Overview);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create', () => {
    fixture.detectChanges();
    httpMock.expectOne('/api/health').flush({ status: 'UP', service: 'media-platform-api' });

    expect(component).toBeTruthy();
  });

  it('reports the backend as online when the health check succeeds', () => {
    fixture.detectChanges();

    httpMock.expectOne('/api/health').flush({ status: 'UP', service: 'media-platform-api' });

    expect(component['backendStatus']()).toBe('online');
  });

  it('reports the backend as offline when the health check fails', () => {
    fixture.detectChanges();

    httpMock.expectOne('/api/health').error(new ProgressEvent('network error'));

    expect(component['backendStatus']()).toBe('offline');
  });
});
