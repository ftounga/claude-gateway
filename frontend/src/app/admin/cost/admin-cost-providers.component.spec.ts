import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { formatEuros } from '../../shared/euros';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AdminCostProvidersComponent } from './admin-cost-providers.component';
import { AdminCostService } from './admin-cost.service';
import { providerSpend } from '../../shared/provider-gauges/provider-spend.testing';

/** La carte « Les deux pots — ce mois » (F-279 / SF-279-02). */
describe('AdminCostProvidersComponent (F-279 / SF-279-02)', () => {
  let service: jasmine.SpyObj<AdminCostService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function setup(): HTMLElement {
    TestBed.configureTestingModule({
      imports: [AdminCostProvidersComponent, NoopAnimationsModule],
      providers: [
        { provide: AdminCostService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    const fixture = TestBed.createComponent(AdminCostProvidersComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<AdminCostService>('AdminCostService', ['providers', 'refreshProviders']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
  });

  it('une ligne par pot : montant, paiement, recalé/estimé, note ; total ; canal ; inexactitudes', () => {
    service.providers.and.returnValue(of(providerSpend()));
    const el = setup();
    const text = el.textContent ?? '';
    expect(text).toContain('Les deux pots — ce mois');
    expect(text).toContain('En service : AWS Bedrock');
    expect(text).toContain('crédit Anthropic épuisé, relais Bedrock');
    const pots = el.querySelectorAll('.pots__pot');
    expect(pots.length).toBe(2);
    expect(pots[0].textContent).toContain(formatEuros(1060.12));
    expect(pots[0].textContent).toContain('Crédit prépayé');
    expect(pots[0].textContent).toContain(`Recalé sur la facture : ${formatEuros(1000)} (jusqu’au 25/11)`);
    expect(pots[0].textContent).toContain('Écart facture − calcul (jours recalés) : 8,50 €');
    expect(pots[1].textContent).toContain('Facturé par AWS en fin de mois');
    expect(pots[1].textContent).toContain('ce:GetCostAndUsage');
    expect(pots[1].textContent).not.toContain('Écart');
    expect(text).toContain(`Total du mois : ${formatEuros(1126.52)}`);
    expect(text).toContain('Hors TVA');
    expect(text).toContain('1 $ = 0,92 €');
  });

  it('Relire maintenant : succès annoncé ; échec annoncé, données gardées', () => {
    service.providers.and.returnValue(of(providerSpend()));
    service.refreshProviders.and.returnValue(of(providerSpend({ current: 'ANTHROPIC', relay: false })));
    const el = setup();
    (el.querySelector('button') as HTMLButtonElement).click();
    expect(service.refreshProviders).toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith('Factures relues.', 'OK', { duration: 3000 });

    service.refreshProviders.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    (el.querySelector('button') as HTMLButtonElement).click();
    expect(snackBar.open).toHaveBeenCalledWith('La facture n’a pas pu être relue.', 'Fermer', { duration: 5000 });
  });

  it('403 : réservée à l’administration', () => {
    service.providers.and.returnValue(throwError(() => new HttpErrorResponse({ status: 403 })));
    const text = setup().textContent ?? '';
    expect(text).toContain('réservée à l’administration');
    expect(text).not.toContain('Total du mois');
  });
});
