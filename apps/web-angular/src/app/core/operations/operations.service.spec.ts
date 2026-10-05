import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { OperationsService } from './operations.service';

describe('OperationsService', () => {
  let service: OperationsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(OperationsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the overview with credentials', () => {
    service.overview().subscribe();
    const request = http.expectOne('/api/operations/overview');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    request.flush({});
  });

  it('passes bounded pagination and filters as query parameters', () => {
    service.jobs(2, 10, 'FAILED', 'PUBLISH_MEDIA').subscribe();
    const request = http.expectOne((r) => r.url === '/api/operations/jobs');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('10');
    expect(request.request.params.get('status')).toBe('FAILED');
    expect(request.request.params.get('type')).toBe('PUBLISH_MEDIA');
    request.flush({});
  });

  it('omits empty filters', () => {
    service.workers(0, 25).subscribe();
    const request = http.expectOne((r) => r.url === '/api/operations/workers');
    expect(request.request.params.has('state')).toBe(false);
    request.flush({});
  });

  it('acknowledges an incident with a POST that carries credentials', () => {
    service.acknowledge('abc').subscribe();
    const request = http.expectOne('/api/operations/incidents/abc/acknowledge');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBe(true);
    request.flush({});
  });

  it('requests active incidents by default', () => {
    service.incidents().subscribe();
    const request = http.expectOne((r) => r.url === '/api/operations/incidents');
    expect(request.request.params.get('status')).toBe('ACTIVE');
    request.flush({});
  });

  it('encodes identifiers in the path', () => {
    service.job('a/b').subscribe();
    http.expectOne('/api/operations/jobs/a%2Fb').flush({});
  });
});
