import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import { SocialAccountSummary, SocialPlatformAvailability } from '../../core/social-accounts/social-account.models';
import { Settings } from './settings';

describe('Settings', () => {
  let component: Settings;
  let fixture: ComponentFixture<Settings>;
  let socialAccountsService: Pick<SocialAccountsService, 'list' | 'create' | 'disconnect' | 'platforms' | 'connectInstagram'>;

  beforeEach(async () => {
    socialAccountsService = {
      list: vi.fn().mockReturnValue(of([account('ACTIVE')])),
      create: vi.fn().mockReturnValue(of(account('ACTIVE'))),
      disconnect: vi.fn().mockReturnValue(of({ ...instagramAccount('DISCONNECTED') })),
      platforms: vi.fn().mockReturnValue(of(availability(false))),
      connectInstagram: vi.fn().mockReturnValue(of({ authorizationUrl: 'https://www.instagram.com/oauth/authorize?state=abc' })),
    };

    await TestBed.configureTestingModule({
      imports: [Settings],
      providers: [provideRouter([]), { provide: SocialAccountsService, useValue: socialAccountsService }],
    }).compileComponents();

    fixture = TestBed.createComponent(Settings);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('lists existing social accounts and labels the TEST platform as non-real', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('My TEST Account');
    expect(fixture.nativeElement.textContent).toContain('Active');
    expect(fixture.nativeElement.textContent).toContain('never posts to a real platform');
  });

  it('renders empty state when there are no social accounts yet', async () => {
    vi.mocked(socialAccountsService.list).mockReturnValue(of([]));
    fixture = TestBed.createComponent(Settings);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No social accounts yet.');
  });

  it('renders an error state when accounts cannot be loaded', async () => {
    vi.mocked(socialAccountsService.list).mockReturnValue(throwError(() => new Error('Network failure')));
    fixture = TestBed.createComponent(Settings);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Social accounts could not be loaded.');
  });

  it('requires a display name before creating a TEST account', () => {
    fixture.detectChanges();

    component['displayName'].set('   ');
    component['createAccount']();

    expect(socialAccountsService.create).not.toHaveBeenCalled();
    expect(component['createError']()).toBe('Enter a display name for the account.');
  });

  it('creates a TEST account and prepends it to the list', () => {
    fixture.detectChanges();

    component['displayName'].set('New TEST Account');
    vi.mocked(socialAccountsService.create).mockReturnValue(of({ ...account('ACTIVE'), id: 'account-2', displayName: 'New TEST Account' }));
    component['createAccount']();

    expect(socialAccountsService.create).toHaveBeenCalledWith('TEST', 'New TEST Account');
    expect(component['accounts']()[0].displayName).toBe('New TEST Account');
    expect(component['displayName']()).toBe('');
  });

  it('surfaces an error when account creation fails', () => {
    fixture.detectChanges();
    vi.mocked(socialAccountsService.create).mockReturnValue(throwError(() => new Error('Network failure')));

    component['displayName'].set('New TEST Account');
    component['createAccount']();

    expect(component['createError']()).toBe('Social account could not be created.');
  });

  it('hides the Connect Instagram action when Instagram is not configured', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component['instagramAvailable']()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Instagram is not configured on this server yet.');
    expect(fixture.nativeElement.textContent).not.toContain('Connect Instagram');
  });

  it('shows Connect Instagram and starts the official OAuth flow when configured', async () => {
    vi.mocked(socialAccountsService.platforms).mockReturnValue(of(availability(true)));
    fixture = TestBed.createComponent(Settings);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component['instagramAvailable']()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Connect Instagram');

    const originalLocation = window.location;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    delete (window as any).location;
    (window as any).location = { href: '' };

    component['connectInstagram']();

    expect(socialAccountsService.connectInstagram).toHaveBeenCalled();
    expect(window.location.href).toBe('https://www.instagram.com/oauth/authorize?state=abc');

    (window as any).location = originalLocation;
  });

  it('surfaces an error when starting the Instagram connection fails', () => {
    vi.mocked(socialAccountsService.connectInstagram).mockReturnValue(throwError(() => new Error('Network failure')));
    fixture.detectChanges();

    component['connectInstagram']();

    expect(component['connectError']()).toBe('Instagram could not be connected right now.');
  });

  it('offers Disconnect only for active Instagram accounts, never for TEST', async () => {
    vi.mocked(socialAccountsService.list).mockReturnValue(of([account('ACTIVE'), instagramAccount('ACTIVE')]));
    fixture = TestBed.createComponent(Settings);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const disconnectButtons = buttons.filter((button) => button.textContent?.includes('Disconnect'));
    expect(disconnectButtons).toHaveLength(1);
  });

  it('disconnects an Instagram account and reflects its new status', () => {
    const account1 = instagramAccount('ACTIVE');
    component['disconnect'](account1);

    expect(socialAccountsService.disconnect).toHaveBeenCalledWith(account1.id);
  });

  it('shows a success banner after a successful Instagram OAuth callback redirect', async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [Settings],
      providers: [
        provideRouter([]),
        { provide: SocialAccountsService, useValue: socialAccountsService },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ instagram: 'connected' }) } },
        },
      ],
    }).compileComponents();

    const newFixture = TestBed.createComponent(Settings);
    await newFixture.whenStable();
    newFixture.detectChanges();

    expect(newFixture.nativeElement.textContent).toContain('Instagram account connected successfully.');
  });

  it('shows a friendly error banner after a failed Instagram OAuth callback redirect', async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [Settings],
      providers: [
        provideRouter([]),
        { provide: SocialAccountsService, useValue: socialAccountsService },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ instagram: 'error', reason: 'invalid_state' }) } },
        },
      ],
    }).compileComponents();

    const newFixture = TestBed.createComponent(Settings);
    await newFixture.whenStable();
    newFixture.detectChanges();

    expect(newFixture.nativeElement.textContent).toContain('expired or was already used');
  });

  function account(status: SocialAccountSummary['status']): SocialAccountSummary {
    return {
      id: 'account-1',
      platform: 'TEST',
      displayName: 'My TEST Account',
      externalAccountId: null,
      status,
      createdAt: '2026-09-10T08:00:00Z',
      updatedAt: '2026-09-10T08:00:00Z',
    };
  }

  function instagramAccount(status: SocialAccountSummary['status']): SocialAccountSummary {
    return {
      id: 'account-ig-1',
      platform: 'INSTAGRAM',
      displayName: 'creator_handle',
      externalAccountId: '17841400000000000',
      status,
      createdAt: '2026-09-10T08:00:00Z',
      updatedAt: '2026-09-10T08:00:00Z',
    };
  }

  function availability(instagram: boolean): SocialPlatformAvailability {
    return { TEST: true, INSTAGRAM: instagram, TIKTOK: false };
  }
});
