import { DatePipe } from '@angular/common';
import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';

import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import {
  SocialAccountStatus,
  SocialAccountSummary,
  SocialPlatformAvailability,
} from '../../core/social-accounts/social-account.models';

type LoadState = 'loading' | 'ready' | 'error';

const CALLBACK_REASONS: Record<string, string> = {
  denied: 'Instagram authorization was cancelled.',
  invalid_state: 'The Instagram connection request expired or was already used. Please try again.',
  missing_code: 'Instagram did not return an authorization code. Please try again.',
  exchange_failed: 'Instagram could not be connected right now. Please try again in a moment.',
  missing_scope: 'TikTok did not grant video publishing permission. Reconnect and approve video.publish.',
};

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
  protected readonly platformAvailability = signal<SocialPlatformAvailability | null>(null);
  protected readonly connectingInstagram = signal(false);
  protected readonly connectingTikTok = signal(false);
  protected readonly connectError = signal<string | null>(null);
  protected readonly disconnecting = signal<Record<string, boolean>>({});
  protected readonly disconnectErrors = signal<Record<string, string | null>>({});
  protected readonly callbackBanner = signal<{ kind: 'success' | 'error'; message: string } | null>(null);

  constructor(
    private readonly socialAccountsService: SocialAccountsService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    this.refresh();
    this.socialAccountsService.platforms().subscribe({
      next: (availability) => this.platformAvailability.set(availability),
      error: () => this.platformAvailability.set({ TEST: true, INSTAGRAM: false, TIKTOK: false }),
    });
    this.readCallbackBanner();
  }

  protected instagramAvailable(): boolean {
    return this.platformAvailability()?.INSTAGRAM ?? false;
  }

  protected tiktokAvailable(): boolean { return this.platformAvailability()?.TIKTOK ?? false; }

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

  protected connectInstagram(): void {
    this.connectError.set(null);
    this.connectingInstagram.set(true);
    this.socialAccountsService
      .connectInstagram()
      .pipe(finalize(() => this.connectingInstagram.set(false)))
      .subscribe({
        next: (response) => {
          window.location.href = response.authorizationUrl;
        },
        error: () => this.connectError.set('Instagram could not be connected right now.'),
      });
  }

  protected connectTikTok(): void {
    this.connectError.set(null); this.connectingTikTok.set(true);
    this.socialAccountsService.connectTikTok().pipe(finalize(() => this.connectingTikTok.set(false))).subscribe({
      next: (response) => { window.location.href = response.authorizationUrl; },
      error: () => this.connectError.set('TikTok could not be connected right now.'),
    });
  }

  protected disconnect(account: SocialAccountSummary): void {
    this.disconnectErrors.update((errors) => ({ ...errors, [account.id]: null }));
    this.disconnecting.update((busy) => ({ ...busy, [account.id]: true }));
    this.socialAccountsService
      .disconnect(account.id)
      .pipe(finalize(() => this.disconnecting.update((busy) => ({ ...busy, [account.id]: false }))))
      .subscribe({
        next: (updated) => {
          this.accounts.set(this.accounts().map((existing) => (existing.id === updated.id ? updated : existing)));
        },
        error: () => this.disconnectErrors.update((errors) => ({ ...errors, [account.id]: 'Could not disconnect this account.' })),
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

  private readCallbackBanner(): void {
    const params = this.route.snapshot.queryParamMap;
    const platform = params.get('instagram') ? 'instagram' : params.get('tiktok') ? 'tiktok' : null;
    const outcome = platform ? params.get(platform) : null;
    if (!platform || !outcome) {
      return;
    }
    const label = platform === 'tiktok' ? 'TikTok' : 'Instagram';
    if (outcome === 'connected') {
      this.callbackBanner.set({ kind: 'success', message: `${label} account connected successfully.` });
    } else if (outcome === 'error') {
      const reason = params.get('reason') ?? '';
      this.callbackBanner.set({ kind: 'error', message: CALLBACK_REASONS[reason] ?? `${label} could not be connected.` });
    }
    this.router.navigate([], { queryParams: {}, replaceUrl: true });
  }
}
