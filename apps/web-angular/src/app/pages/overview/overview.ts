import { Component, OnInit, signal } from '@angular/core';

import { HealthService } from '../../core/health.service';

type BackendStatus = 'checking' | 'online' | 'offline';

@Component({
  selector: 'app-overview',
  imports: [],
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class Overview implements OnInit {
  protected readonly backendStatus = signal<BackendStatus>('checking');

  constructor(private readonly healthService: HealthService) {}

  ngOnInit(): void {
    this.healthService.check().subscribe({
      next: () => this.backendStatus.set('online'),
      error: () => this.backendStatus.set('offline'),
    });
  }
}
