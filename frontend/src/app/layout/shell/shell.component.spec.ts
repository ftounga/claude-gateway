import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { signal } from '@angular/core';

import { ShellComponent } from './shell.component';
import { AuthService } from '../../core/services/auth.service';
import { QuotaAlertService } from '../../core/services/quota-alert.service';
import { QuotaAlertView } from '../../core/models/quota-alert.models';

describe('ShellComponent', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let authSpy: jasmine.SpyObj<AuthService>;
  let quotaAlertSpy: jasmine.SpyObj<QuotaAlertService>;
  let router: Router;

  /** Aucune alerte levée : la coquille doit se comporter exactement comme avant F-42. */
  const NO_ALERT: QuotaAlertView = {
    raised: false,
    usedTokens: 0,
    quotaTokens: 1000000,
    remainingTokens: 1000000,
    usedPercent: 0,
    thresholdPercent: 80,
    periodEnd: '2026-08-01',
    topUp: null,
  };

  beforeEach(async () => {
    authSpy = jasmine.createSpyObj<AuthService>('AuthService', ['logout'], {
      isAdmin: signal(false),
    });
    authSpy.logout.and.returnValue(
      of({ message: 'ok' }) as unknown as ReturnType<AuthService['logout']>,
    );

    quotaAlertSpy = jasmine.createSpyObj<QuotaAlertService>('QuotaAlertService', [
      'getAlert',
      'dismissAlert',
    ]);
    quotaAlertSpy.getAlert.and.returnValue(of(NO_ALERT));
    quotaAlertSpy.dismissAlert.and.returnValue(of(undefined as unknown as void));

    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        // La bannière d'alerte de quota (F-42) que porte la coquille tire BillingService, donc
        // HttpClient : aucun appel n'est émis ici (le service d'alerte est bouchonné, et aucune
        // alerte n'est levée), mais l'injecteur doit pouvoir le construire.
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authSpy },
        { provide: QuotaAlertService, useValue: quotaAlertSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ShellComponent);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture.detectChanges();
  });

  it('affiche les liens de navigation vers les sections principales', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chat');
    expect(text).toContain('Bibliothèque');
    expect(text).toContain('Templates');
  });

  it('se déconnecte et redirige vers /login', () => {
    fixture.componentInstance.logout();

    expect(authSpy.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  // ---- F-42 SF-42-02 : la bannière d'alerte de quota vit dans la coquille ----
  it('porte la bannière d\'alerte de quota, sans rien rendre quand aucune alerte n\'est levée', () => {
    const shell = fixture.nativeElement as HTMLElement;

    expect(shell.querySelector('app-quota-alert-banner')).not.toBeNull();
    expect(quotaAlertSpy.getAlert).toHaveBeenCalled();
    // Aucune alerte : aucune bande, donc aucun décalage de mise en page sur les écrans enveloppés.
    expect(shell.querySelector('.quota-alert')).toBeNull();
  });

  it('conserve la navigation intacte au-dessus du contenu (non-régression F-42)', () => {
    const shell = fixture.nativeElement as HTMLElement;

    // La bannière s'intercale entre la barre de navigation et le contenu, sans les remplacer.
    expect(shell.querySelector('mat-toolbar.app-bar')).not.toBeNull();
    expect(shell.querySelector('main.app-content router-outlet')).not.toBeNull();
  });

  // ---- F-54 SF-54-02 : la bulle d'aide vit dans la coquille, donc côté authentifié ----
  it("porte la bulle d'aide produit, panneau fermé au départ", () => {
    const shell = fixture.nativeElement as HTMLElement;

    expect(shell.querySelector('app-help-chat-widget')).not.toBeNull();
    expect(shell.querySelector('.help-widget__bubble')).not.toBeNull();
    // Le panneau ne s'ouvre qu'au clic : la bulle n'encombre pas l'écran par défaut.
    expect(shell.querySelector('.help-panel')).toBeNull();
  });

  // ---- F-29 SF-29-01 : garde-fou anti-régression sur la marque de la coquille ----
  it('affiche la marque « Claude Portal » sans le terme « Proxy »', () => {
    const brand = (fixture.nativeElement as HTMLElement).querySelector('.brand');
    expect(brand?.textContent?.trim()).toBe('Claude Portal');
    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toMatch(/proxy/i);
  });
});
