import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { WorkerSummary } from './worker.models';

@Injectable({ providedIn: 'root' })
export class WorkersService {
  constructor(private readonly http: HttpClient) {}

  list(): Observable<WorkerSummary[]> {
    return this.http.get<WorkerSummary[]>(`${environment.apiBaseUrl}/workers`, { withCredentials: true });
  }
}
