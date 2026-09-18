import { DatePipe } from '@angular/common';
import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';

import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import { SocialAccountStatus, SocialAccountSummary } from '../../core/social-accounts/social-account.models';

type LoadState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-settings',
  imports: [DatePipe, FormsModule],
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class Settings implements OnInit {
  protected readonly accounts = signal<SocialAccountSummary[]>([]);
  protected readonly loadState = signal<LoadState>('loading');
  protected readonly displayName = signal('');
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  constructor(private readonly socialAccountsService: SocialAccountsService) {}

  ngOnInit(): void {
    this.refresh();
  }

  protected createAccount(): void {
    this.createError.set(null);
    const name = this.displayName().trim();
    if (!name) {
      this.createError.set('Enter a display name for the account.');
      return;
    }
    this.creating.set(true);
    this.socialAccountsService
      .create('TEST', name)
      .pipe(finalize(() => this.creating.set(false)))
      .subscribe({
        next: (account) => {
          this.accounts.set([account, ...this.accounts()]);
          this.displayName.set('');
        },
        error: () => this.createError.set('Social account could not be created.'),
      });
  }

  protected statusLabel(status: SocialAccountStatus): string {
    switch (status) {
      case 'ACTIVE':
        return 'Active';
      case 'DISCONNECTED':
        return 'Disconnected';
      case 'ERROR':
        return 'Error';
    }
  }

  private refresh(): void {
    this.loadState.set('loading');
    this.socialAccountsService.list().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loadState.set('ready');
      },
      error: () => this.loadState.set('error'),
    });
  }
}
