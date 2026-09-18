import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { SocialAccountsService } from '../../core/social-accounts/social-accounts.service';
import { SocialAccountSummary } from '../../core/social-accounts/social-account.models';
import { Settings } from './settings';

describe('Settings', () => {
  let component: Settings;
  let fixture: ComponentFixture<Settings>;
  let socialAccountsService: Pick<SocialAccountsService, 'list' | 'create'>;

  beforeEach(async () => {
    socialAccountsService = {
      list: vi.fn().mockReturnValue(of([account('ACTIVE')])),
      create: vi.fn().mockReturnValue(of(account('ACTIVE'))),
    };

    await TestBed.configureTestingModule({
      imports: [Settings],
      providers: [{ provide: SocialAccountsService, useValue: socialAccountsService }],
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
});
