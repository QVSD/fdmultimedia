import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';

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

  protected readonly navItems: NavItem[] = [
    { path: '/', label: 'Overview' },
    { path: '/content', label: 'Content' },
    { path: '/robots', label: 'Robots' },
    { path: '/personas', label: 'Personas' },
    { path: '/compute', label: 'Compute' },
    { path: '/jobs', label: 'Jobs' },
    { path: '/settings', label: 'Settings' },
  ];
  protected logout(): void {
    this.auth.logout().subscribe({
      next: () => this.router.navigateByUrl('/login'),
      error: () => this.router.navigateByUrl('/login'),
    });
  }
}
