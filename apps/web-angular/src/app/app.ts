import { Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { isOperator } from './core/auth/operator.guard';

interface NavItem {
  path: string;
  label: string;
}

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly user = this.auth.currentUser;
  protected readonly workspace = this.auth.currentWorkspace;

  private readonly baseNavItems: NavItem[] = [
    { path: '/', label: 'Overview' },
    { path: '/content', label: 'Content' },
    { path: '/robots', label: 'Robots' },
    { path: '/personas', label: 'Personas' },
    { path: '/experiments', label: 'Experiments' },
    { path: '/compute', label: 'Compute' },
    { path: '/jobs', label: 'Jobs' },
  ];

  /** Operations is visible only to workspace operators (OWNER and ADMIN); everyone else never sees the entry. */
  protected readonly navItems = computed<NavItem[]>(() => [
    ...this.baseNavItems,
    ...(isOperator(this.workspace()?.role) ? [{ path: '/operations', label: 'Operations' }] : []),
    { path: '/settings', label: 'Settings' },
  ]);
  protected logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigateByUrl('/login'),
      error: () => this.router.navigateByUrl('/login'),
    });
  }
}
