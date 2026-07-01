import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { SettingsComponent } from './settings.component';
import { AccountService } from '../core/services/account.service';
import { AuthService } from '../core/services/auth.service';
import { AccountExport } from '../core/models/account.models';
import { UserProfile } from '../core/models/auth.models';

describe('SettingsComponent', () => {
  let fixture: ComponentFixture<SettingsComponent>;
  let component: SettingsComponent;
  let accountService: jasmine.SpyObj<AccountService>;
  let authService: jasmine.SpyObj<AuthService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let router: Router;

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
});
