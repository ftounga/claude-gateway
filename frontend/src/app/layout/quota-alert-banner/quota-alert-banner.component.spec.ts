import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, of, throwError } from 'rxjs';

import { QuotaAlertBannerComponent } from './quota-alert-banner.component';
import { QuotaAlertService } from '../../core/services/quota-alert.service';
import { BillingService } from '../../core/services/billing.service';
import { QuotaAlertView } from '../../core/models/quota-alert.models';
import { CheckoutResponse } from '../../core/models/billing.models';

/** Alerte levée type : 85 % de 1 M jetons, pack STANDARD proposé. */
const RAISED: QuotaAlertView = {
  raised: true,
  usedTokens: 850000,
  quotaTokens: 1000000,
  remainingTokens: 150000,
  usedPercent: 85,
  thresholdPercent: 80,
  periodEnd: '2026-08-01',
  topUp: { code: 'STANDARD', label: '1 M jetons', tokens: 1000000, priceEur: null },
};

describe('QuotaAlertBannerComponent', () => {
  let fixture: ComponentFixture<QuotaAlertBannerComponent>;
  let component: QuotaAlertBannerComponent;
  let alertSpy: jasmine.SpyObj<QuotaAlertService>;
  let billingSpy: jasmine.SpyObj<BillingService>;
  let snackSpy: jasmine.SpyObj<MatSnackBar>;

  /** Monte le composant avec l'alerte (ou l'erreur) que le service doit renvoyer. */
  async function build(alert: Observable<QuotaAlertView>): Promise<void> {
    alertSpy = jasmine.createSpyObj<QuotaAlertService>('QuotaAlertService', [
      'getAlert',
      'dismissAlert',
    ]);
    alertSpy.getAlert.and.returnValue(alert);
    alertSpy.dismissAlert.and.returnValue(of(undefined as unknown as void));

    billingSpy = jasmine.createSpyObj<BillingService>('BillingService', ['startTopUpCheckout']);
    billingSpy.startTopUpCheckout.and.returnValue(
      of({ checkoutUrl: 'https://checkout.example/pay' } as CheckoutResponse),
    );

    snackSpy = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [QuotaAlertBannerComponent],
      providers: [
        provideNoopAnimations(),
        { provide: QuotaAlertService, useValue: alertSpy },
        { provide: BillingService, useValue: billingSpy },
        { provide: MatSnackBar, useValue: snackSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(QuotaAlertBannerComponent);
    component = fixture.componentInstance;
    spyOn(component as unknown as { redirect: (u: string) => void }, 'redirect');
    fixture.detectChanges();
  }

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('ne rend aucun élément de bannière tant que le seuil n\'est pas franchi', async () => {
    await build(of({ ...RAISED, raised: false, topUp: null }));

    expect(component.visible()).toBeFalse();
    expect(html().querySelector('.quota-alert')).toBeNull();
  });

  it('affiche le pourcentage, les jetons restants et le pack quand l\'alerte est levée', async () => {
    await build(of(RAISED));

    const banner = html().querySelector('.quota-alert');
    expect(banner).not.toBeNull();
    const text = banner!.textContent ?? '';
    expect(text).toContain('85 %');
    expect(text).toContain('jusqu\'au');
    // Le libellé du bouton vient du pack renvoyé par l'API, jamais d'une constante du composant.
    expect(text).toContain('Recharger — 1 M jetons');
  });

  it('dit le prix sur le bouton quand le serveur en envoie un (F-67)', async () => {
    // Ce bouton mène DROIT au paiement : faire cliquer sans dire ce qu'on engage n'est pas
    // acceptable en vente en ligne.
    await build(of({ ...RAISED, topUp: { ...RAISED.topUp!, priceEur: '4,99' } }));

    expect(html().querySelector('.quota-alert')!.textContent).toContain(
      'Recharger — 1 M jetons · 4,99 €',
    );
  });

  it('n\'invente aucun montant quand le pack n\'en a pas (F-67)', async () => {
    // Cas LIVRÉ du pack 1 M : le prix appartient au PO. Le bouton garde son libellé, le montant
    // sera donné par la page de paiement.
    await build(of(RAISED));

    const text = html().querySelector('.quota-alert')!.textContent ?? '';
    expect(text).toContain('Recharger — 1 M jetons');
    expect(text).not.toContain('€');
  });

  it('recharge en un clic : checkout sur le pack de l\'alerte puis redirection', async () => {
    await build(of(RAISED));

    component.recharge();

    expect(billingSpy.startTopUpCheckout).toHaveBeenCalledOnceWith('STANDARD');
    expect(
      (component as unknown as { redirect: jasmine.Spy }).redirect,
    ).toHaveBeenCalledWith('https://checkout.example/pay');
  });

  it('ignore un second clic : une seule session de paiement est créée', async () => {
    await build(of(RAISED));
    // Checkout qui ne se termine jamais : la garde doit tenir entre les deux clics.
    billingSpy.startTopUpCheckout.and.returnValue(new Observable<CheckoutResponse>());

    component.recharge();
    component.recharge();

    expect(billingSpy.startTopUpCheckout).toHaveBeenCalledTimes(1);
  });

  it('signale une facturation indisponible et garde la bannière affichée', async () => {
    await build(of(RAISED));
    billingSpy.startTopUpCheckout.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 503, error: { error: 'billing_unavailable' } })),
    );

    component.recharge();

    expect(snackSpy.open).toHaveBeenCalled();
    expect(snackSpy.open.calls.mostRecent().args[0]).toContain('momentanément indisponible');
    // La bannière reste : l'utilisateur doit pouvoir réessayer.
    expect(component.visible()).toBeTrue();
    expect(component.redirecting()).toBeFalse();
  });

  it('signale une erreur générique de rachat', async () => {
    await build(of(RAISED));
    billingSpy.startTopUpCheckout.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );

    component.recharge();

    expect(snackSpy.open.calls.mostRecent().args[0]).toContain('Impossible de démarrer le rachat');
  });

  it('écarte l\'alerte : appel au serveur et masquage de la bannière', async () => {
    await build(of(RAISED));

    component.dismiss();
    fixture.detectChanges();

    expect(alertSpy.dismissAlert).toHaveBeenCalled();
    expect(component.visible()).toBeFalse();
    expect(html().querySelector('.quota-alert')).toBeNull();
  });

  it('masque la bannière même si l\'écartement échoue côté serveur', async () => {
    await build(of(RAISED));
    alertSpy.dismissAlert.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );

    component.dismiss();
    fixture.detectChanges();

    // L'utilisateur a demandé à ne plus la voir : une panne serveur ne doit pas la lui réimposer.
    expect(component.visible()).toBeFalse();
  });

  it('ne rend rien et n\'affiche aucune erreur si le chargement de l\'alerte échoue', async () => {
    await build(throwError(() => new HttpErrorResponse({ status: 500 })));

    expect(component.alert()).toBeNull();
    expect(html().querySelector('.quota-alert')).toBeNull();
    expect(snackSpy.open).not.toHaveBeenCalled();
  });

  it('affiche l\'alerte sans bouton de recharge quand aucun pack n\'est proposé', async () => {
    await build(of({ ...RAISED, topUp: null }));

    const text = html().querySelector('.quota-alert')?.textContent ?? '';
    expect(text).toContain('85 %');
    expect(text).not.toContain('Recharger');
    expect(text).toContain('Ignorer');

    // Et un clic sur « recharger » sans pack ne déclenche aucun paiement.
    component.recharge();
    expect(billingSpy.startTopUpCheckout).not.toHaveBeenCalled();
  });
});
