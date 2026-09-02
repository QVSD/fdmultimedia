import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

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
  protected readonly navItems: NavItem[] = [
    { path: '/', label: 'Overview' },
    { path: '/compute', label: 'Compute' },
    { path: '/content', label: 'Content' },
    { path: '/robots', label: 'Robots' },
    { path: '/jobs', label: 'Jobs' },
    { path: '/analytics', label: 'Analytics' },
    { path: '/revenue', label: 'Revenue' },
    { path: '/settings', label: 'Settings' },
  ];
}
