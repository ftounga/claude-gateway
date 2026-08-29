import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { SettingsComponent } from './settings.component';
import { AccountService } from '../core/services/account.service';
import { ApiKeyService } from '../core/services/api-key.service';
import { AuthService } from '../core/services/auth.service';
import { GitTokenService } from '../core/services/git-token.service';
import { AccountExport } from '../core/models/account.models';
import { ApiKeyStatus } from '../core/models/api-key.models';
import { GitTokenStatus } from '../core/models/git-token.models';
import { UserProfile } from '../core/models/auth.models';

describe('SettingsComponent', () => {
  let fixture: ComponentFixture<SettingsComponent>;
  let component: SettingsComponent;
  let accountService: jasmine.SpyObj<AccountService>;
  let apiKeyService: jasmine.SpyObj<ApiKeyService>;
  let gitTokenService: jasmine.SpyObj<GitTokenService>;
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

  const absentGitToken: GitTokenStatus = {
    present: false,
    githubLogin: null,
    maskedToken: null,
    last4: null,
    createdAt: null,
    updatedAt: null,
  };

  const presentGitToken: GitTokenStatus = {
    present: true,
    githubLogin: 'octocat',
    maskedToken: '…AB12',
    last4: 'AB12',
    createdAt: '2026-08-25T10:00:00Z',
    updatedAt: '2026-08-25T10:00:00Z',
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
    gitTokenService = jasmine.createSpyObj<GitTokenService>('GitTokenService', [
      'getStatus',
      'saveToken',
      'deleteToken',
    ]);
    gitTokenService.getStatus.and.returnValue(of(absentGitToken));
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
        { provide: GitTokenService, useValue: gitTokenService },
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
  // --- Jeton GitHub (F-31) ---

  it('loads the github token status on init', () => {
    setup();
    expect(gitTokenService.getStatus).toHaveBeenCalled();
    expect(component.gitTokenStatus()).toEqual(absentGitToken);
  });

  it('saves the github token, clears the field and shows the account', () => {
    setup();
    gitTokenService.saveToken.and.returnValue(of(presentGitToken));
    component.gitTokenControl.setValue('github_pat_secret_AB12');

    component.saveGitToken();

    expect(gitTokenService.saveToken).toHaveBeenCalledWith({ token: 'github_pat_secret_AB12' });
    expect(component.gitTokenStatus()).toEqual(presentGitToken);
    expect(component.gitTokenControl.value).toBe('');
  });

  it('does not save when the github token field is empty', () => {
    setup();
    component.gitTokenControl.setValue('');

    component.saveGitToken();

    expect(gitTokenService.saveToken).not.toHaveBeenCalled();
    expect(component.gitTokenControl.touched).toBeTrue();
  });

  it('keeps the previous github token when the save fails', () => {
    setup();
    gitTokenService.getStatus.and.returnValue(of(presentGitToken));
    component.loadGitToken();
    gitTokenService.saveToken.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400 })),
    );
    component.gitTokenControl.setValue('github_pat_revoked');

    component.saveGitToken();

    expect(component.gitTokenStatus()).toEqual(presentGitToken);
    expect(component.savingGitToken()).toBeFalse();
  });

  it('reports a github outage distinctly from a refused token', () => {
    setup();
    gitTokenService.saveToken.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 503 })),
    );
    component.gitTokenControl.setValue('github_pat_valid');

    component.saveGitToken();

    expect(component.gitTokenStatus()).toEqual(absentGitToken);
    expect(component.savingGitToken()).toBeFalse();
  });

  it('removes the github token when confirmed', () => {
    setup(true);
    gitTokenService.deleteToken.and.returnValue(of(void 0));

    component.deleteGitToken();

    expect(dialog.open).toHaveBeenCalled();
    expect(gitTokenService.deleteToken).toHaveBeenCalled();
    expect(component.gitTokenStatus()?.present).toBeFalse();
    expect(component.gitTokenStatus()?.githubLogin).toBeNull();
  });

  it('does not remove the github token when confirmation is cancelled', () => {
    setup(false);

    component.deleteGitToken();

    expect(dialog.open).toHaveBeenCalled();
    expect(gitTokenService.deleteToken).not.toHaveBeenCalled();
  });
  /**
   * Non-régression SF-31-06 — le chemin réellement cassé en production : DOM → directive → handler.
   *
   * Les autres tests appellent `component.saveGitToken()` / `saveApiKey()` directement, ce qui ne
   * prouve rien du template : sans `FormsModule`, aucune directive ne s'applique aux <form> (ils
   * n'ont pas de [formGroup]), `(ngSubmit)` n'est jamais émis, et le navigateur soumet nativement —
   * la page se recharge et rien n'est enregistré. Ces tests exercent le submit du DOM.
   */
  describe('soumission des formulaires depuis le DOM (SF-31-06)', () => {
    /** Le <form> portant le champ du jeton GitHub, identifié par son champ et non par son rang. */
    function gitTokenForm(): HTMLFormElement {
      const form = (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLInputElement>('input[placeholder="github_pat_..."]')
        ?.closest('form');
      expect(form).withContext('formulaire du jeton GitHub introuvable').not.toBeNull();
      return form as HTMLFormElement;
    }

    /** Le <form> portant le champ de la clé Anthropic (BYOK, F-03) — même défaut, même écran. */
    function apiKeyForm(): HTMLFormElement {
      const form = (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLInputElement>('input[placeholder="sk-ant-..."]')
        ?.closest('form');
      expect(form).withContext('formulaire de la clé API introuvable').not.toBeNull();
      return form as HTMLFormElement;
    }

    /** Soumet comme le ferait le navigateur, et rend l'événement pour inspecter `preventDefault`. */
    function submit(form: HTMLFormElement): Event {
      const event = new Event('submit', { bubbles: true, cancelable: true });
      form.dispatchEvent(event);
      return event;
    }

    it('submitting the github token form calls the service with the typed token', () => {
      setup();
      gitTokenService.saveToken.and.returnValue(of(presentGitToken));
      component.gitTokenControl.setValue('github_pat_secret_AB12');

      submit(gitTokenForm());

      expect(gitTokenService.saveToken).toHaveBeenCalledWith({ token: 'github_pat_secret_AB12' });
      expect(component.gitTokenStatus()).toEqual(presentGitToken);
    });

    it('prevents the native submit that would reload the page', () => {
      setup();
      gitTokenService.saveToken.and.returnValue(of(presentGitToken));
      component.gitTokenControl.setValue('github_pat_secret_AB12');

      // Le cœur du défaut : sans NgForm, `defaultPrevented` reste false et le navigateur navigue.
      expect(submit(gitTokenForm()).defaultPrevented).toBeTrue();
    });

    it('submitting the api key form calls the service with the typed key', () => {
      setup();
      apiKeyService.saveKey.and.returnValue(of(presentKey));
      component.apiKeyControl.setValue('sk-ant-secret-AB12');

      const event = submit(apiKeyForm());

      expect(apiKeyService.saveKey).toHaveBeenCalledWith({ apiKey: 'sk-ant-secret-AB12' });
      expect(event.defaultPrevented).toBeTrue();
    });

    it('submitting an empty github token form sends nothing and flags the field', () => {
      setup();

      submit(gitTokenForm());

      expect(gitTokenService.saveToken).not.toHaveBeenCalled();
      expect(component.gitTokenControl.touched).toBeTrue();
    });

    it('keeps the save buttons of type submit inside their own form', () => {
      setup();
      const button = gitTokenForm().querySelector<HTMLButtonElement>('button[type="submit"]');
      expect(button).not.toBeNull();
      expect(button?.textContent).toContain('Enregistrer le jeton');
    });
  });
});
