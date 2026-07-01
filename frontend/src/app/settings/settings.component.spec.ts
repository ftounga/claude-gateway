import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { SettingsComponent } from './settings.component';
import { AccountService } from '../core/services/account.service';
import { ApiKeyService } from '../core/services/api-key.service';
import { AuthService } from '../core/services/auth.service';
import { AccountExport } from '../core/models/account.models';
import { ApiKeyStatus } from '../core/models/api-key.models';
import { UserProfile } from '../core/models/auth.models';

describe('SettingsComponent', () => {
  let fixture: ComponentFixture<SettingsComponent>;
  let component: SettingsComponent;
  let accountService: jasmine.SpyObj<AccountService>;
  let apiKeyService: jasmine.SpyObj<ApiKeyService>;
  let authService: jasmine.SpyObj<AuthService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let router: Router;

  const absentKey: ApiKeyStatus = {
    present: false,
    maskedKey: null,
    last4: null,
    provider: null,
    mode: 'HOSTED',
    validatedAt: null,
    createdAt: null,
  };

  const presentKey: ApiKeyStatus = {
    present: true,
    maskedKey: 'sk-…AB12',
    last4: 'AB12',
    provider: 'ANTHROPIC',
    mode: 'BYOK',
    validatedAt: '2026-07-01T12:00:00Z',
    createdAt: '2026-07-01T12:00:00Z',
  };

  const profile: UserProfile = {
    id: 'u1',
    email: 'alice@example.com',
    emailVerified: true,
    provider: 'LOCAL',
    role: 'USER',
  };

  const exportDoc: AccountExport = {
    exportedAt: '2026-07-01T12:00:00Z',
    account: { ...profile, createdAt: '2026-06-01T10:00:00Z' },
    subscription: null,
    usage: [],
    conversations: [],
    uploadedFiles: [],
  };

  function setup(dialogResult: boolean | undefined = true): void {
    accountService = jasmine.createSpyObj<AccountService>('AccountService', [
      'exportData',
      'deleteAccount',
    ]);
    apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', [
      'getStatus',
      'saveKey',
      'deleteKey',
      'setMode',
    ]);
    apiKeyService.getStatus.and.returnValue(of(absentKey));
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['me', 'clearToken']);
    authService.me.and.returnValue(of(profile));
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.returnValue({ afterClosed: () => of(dialogResult) } as ReturnType<
      MatDialog['open']
    >);

    TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: AccountService, useValue: accountService },
        { provide: ApiKeyService, useValue: apiKeyService },
        { provide: AuthService, useValue: authService },
        { provide: MatDialog, useValue: dialog },
      ],
    });

    fixture = TestBed.createComponent(SettingsComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    fixture.detectChanges();
  }

  it('loads the current account on init', () => {
    setup();
    expect(authService.me).toHaveBeenCalled();
    expect(component.profile()).toEqual(profile);
  });

  it('exports data and triggers a download', () => {
    setup();
    accountService.exportData.and.returnValue(of(exportDoc));
    const downloadSpy = spyOn(
      component as unknown as { triggerDownload: (d: AccountExport) => void },
      'triggerDownload',
    );

    component.exportData();

    expect(accountService.exportData).toHaveBeenCalled();
    expect(downloadSpy).toHaveBeenCalledWith(exportDoc);
  });

  it('does not download when the export fails', () => {
    setup();
    accountService.exportData.and.returnValue(throwError(() => new Error('boom')));
    const downloadSpy = spyOn(
      component as unknown as { triggerDownload: (d: AccountExport) => void },
      'triggerDownload',
    );

    component.exportData();

    expect(downloadSpy).not.toHaveBeenCalled();
    expect(component.exporting()).toBeFalse();
  });

  it('deletes the account, clears the token and redirects when confirmed', () => {
    setup(true);
    accountService.deleteAccount.and.returnValue(of({ message: 'ok' }));
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    component.deleteAccount();

    expect(dialog.open).toHaveBeenCalled();
    expect(accountService.deleteAccount).toHaveBeenCalled();
    expect(authService.clearToken).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/login']);
  });

  it('does not delete the account when the confirmation is cancelled', () => {
    setup(false);

    component.deleteAccount();

    expect(dialog.open).toHaveBeenCalled();
    expect(accountService.deleteAccount).not.toHaveBeenCalled();
    expect(authService.clearToken).not.toHaveBeenCalled();
  });

  // --- BYOK (F-03) ---

  it('loads the api key status on init', () => {
    setup();
    expect(apiKeyService.getStatus).toHaveBeenCalled();
    expect(component.apiKeyStatus()).toEqual(absentKey);
  });

  it('saves the api key and updates the status', () => {
    setup();
    apiKeyService.saveKey.and.returnValue(of(presentKey));
    component.apiKeyControl.setValue('sk-ant-secret-AB12');

    component.saveApiKey();

    expect(apiKeyService.saveKey).toHaveBeenCalledWith({ apiKey: 'sk-ant-secret-AB12' });
    expect(component.apiKeyStatus()).toEqual(presentKey);
    expect(component.apiKeyControl.value).toBe('');
  });

  it('does not save when the api key field is empty', () => {
    setup();
    component.apiKeyControl.setValue('');

    component.saveApiKey();

    expect(apiKeyService.saveKey).not.toHaveBeenCalled();
    expect(component.apiKeyControl.touched).toBeTrue();
  });

  it('deletes the api key when confirmed', () => {
    setup(true);
    apiKeyService.deleteKey.and.returnValue(of(void 0));

    component.deleteApiKey();

    expect(dialog.open).toHaveBeenCalled();
    expect(apiKeyService.deleteKey).toHaveBeenCalled();
    expect(component.apiKeyStatus()?.present).toBeFalse();
    expect(component.apiKeyStatus()?.mode).toBe('HOSTED');
  });

  it('does not delete the api key when confirmation is cancelled', () => {
    setup(false);

    component.deleteApiKey();

    expect(dialog.open).toHaveBeenCalled();
    expect(apiKeyService.deleteKey).not.toHaveBeenCalled();
  });

  it('toggles the provider mode', () => {
    setup();
    apiKeyService.setMode.and.returnValue(of({ ...presentKey, mode: 'HOSTED' }));

    component.setMode('HOSTED');

    expect(apiKeyService.setMode).toHaveBeenCalledWith({ mode: 'HOSTED' });
    expect(component.apiKeyStatus()?.mode).toBe('HOSTED');
  });
});
