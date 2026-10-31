import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AdminCostComponent } from './admin-cost.component';
import { AdminCostService } from './admin-cost.service';
import { AdminCostSummary, CostReconciliation, formatEuros } from './admin-cost.models';

/**
 * Section « Coût réel » de l'administration (F-133 / SF-133-07).
 *
 * Le test qui compte est `n'affiche aucune part quand le client n'a pas de budget` : une part
 * inventée serait pire que pas de part du tout.
 */
describe('AdminCostComponent', () => {
  let fixture: ComponentFixture<AdminCostComponent>;
  let component: AdminCostComponent;
  let service: jasmine.SpyObj<AdminCostService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const summary = (over: Partial<AdminCostSummary> = {}): AdminCostSummary => ({
    period: 'week',
    from: '2026-09-14',
    to: '2026-09-20',
    spentEur: 47.8,
    budgetEur: 120,
    percent: 40,
    clients: [
      {
        hostId: 'h1',
        hostName: 'poste-cagip',
        spentEur: 31.2,
        budgetEur: 60,
        percent: 52,
        ownBudget: true,
        totalTokens: 1000,
      },
    ],
    ...over,
  });

  let recon: CostReconciliation = {
    configured: false, setupHint: 'Créez une clé Admin… APP_ANTHROPIC_ADMIN_KEY', lastFetchedAt: null,
    billedTotalUsd: 0, calculatedTotalUsd: 0, days: [],
  };

  function setup(): void {
    service = jasmine.createSpyObj<AdminCostService>('AdminCostService', [
      'summary', 'alerts', 'setDefaultBudget', 'setHostBudget', 'clearHostBudget', 'reconciliation',
      'refreshReconciliation',
    ]);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.summary.and.returnValue(of(summary()));
    service.alerts.and.returnValue(of([]));
    service.reconciliation.and.returnValue(of(recon));
    service.refreshReconciliation.and.returnValue(of(recon));

    TestBed.configureTestingModule({
      imports: [AdminCostComponent, NoopAnimationsModule],
      providers: [
        { provide: AdminCostService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(AdminCostComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  // ---------------------------------------- F-224 / SF-224-04 : ce que je paie

  it('F-224 / SF-224-05 : tout en euros, le taux en mention discrète ; « 7 derniers jours » offert', () => {
    setup();
    service.summary.and.returnValue(of(summary({ period: '7d', spentUsd: 1234.5, spentEur: 1135.74, usdToEur: 0.92,
      clients: [{ hostId: 'h1', hostName: 'poste-cagip', spentEur: 31.2, budgetEur: 60, percent: 52, ownBudget: true,
        totalTokens: 1000, spentUsd: 33.91 }] })));
    component.selectPeriod('7d');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(service.summary).toHaveBeenCalledWith('7d');
    expect(el.querySelector('.cost-admin__total-label')?.textContent).toContain('7 derniers jours');
    expect(el.querySelector('.cost-admin__total-amount')?.textContent).toContain('1\u202f135,74 €');
    expect(el.querySelector('.cost-admin__total-amount')?.textContent).not.toContain('$');
    expect(el.querySelector('.cost-admin__total-eur')?.textContent?.trim()).toBe('taux 1 $ = 0,92 €');
    const client = el.querySelector('.cost-admin__client-spent')?.textContent ?? '';
    expect(client).toContain('31,20 €');
    expect(client).not.toContain('$');
    // Le dollar ne survit que dans la mention du taux.
    expect((el.querySelector('.cost-admin__card')?.textContent ?? '').split('$').length - 1).toBe(1);
    expect(formatEuros(-2.5)).toBe('−2,50 €');
    expect(formatEuros(undefined)).toBe('—');
  });

  it('F-224 / SF-224-05 : sans taux rendu (passerelle antérieure), les euros sans mention', () => {
    setup();
    service.summary.and.returnValue(of(summary({ spentEur: 12 })));
    component.reload();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.cost-admin__total-amount')?.textContent).toContain('12,00 €');
    expect(el.querySelector('.cost-admin__total-eur')).toBeNull();
  });

  it('F-224 : sans clé Admin, la facture dit comment la créer ; avec, facturé / calculé / écart et les clés', () => {
    setup();
    expect(text()).toContain('Rapprochement inactif');
    expect(text()).toContain('APP_ANTHROPIC_ADMIN_KEY');
    expect((fixture.nativeElement as HTMLElement).querySelector('.recon__refresh')).toBeNull();

    recon = {
      configured: true, setupHint: null, lastFetchedAt: '2026-10-31T06:20:00Z', billedTotalUsd: 80,
      calculatedTotalUsd: 50.25, usdToEur: 0.92, billedTotalEur: 73.6, calculatedTotalEur: 46.23,
      days: [
        { day: '2026-10-31', billedUsd: 80, calculatedUsd: 50.25, gapUsd: 29.75,
          billedEur: 73.6, calculatedEur: 46.23, gapEur: 27.37,
          keys: [{ apiKeyId: 'apikey_lc', label: 'legalcase (…owAA)', amountUsd: 8, amountEur: 7.36,
            inputTokens: 1, outputTokens: 1 }] },
        // Un écart sous 1 € ne se met pas en évidence (même s'il dépasse 1 $ … ou non) ; un jour non lu : « — ».
        { day: '2026-10-30', billedUsd: 1.5, calculatedUsd: 0.9, gapUsd: 0.6,
          billedEur: 1.38, calculatedEur: 0.83, gapEur: 0.55, keys: [] },
        { day: '2026-10-29', billedUsd: null, calculatedUsd: 2, gapUsd: null,
          billedEur: null, calculatedEur: 1.84, gapEur: null, keys: [] },
      ],
    };
    TestBed.resetTestingModule();
    setup();
    const el: HTMLElement = fixture.nativeElement;
    // F-224 / SF-224-05 : facturé, calculé, écart et clés en euros ; une seule mention du taux et de la devise.
    expect(el.querySelector('.recon__totals')?.textContent).toContain('73,60 €');
    expect(el.querySelector('.recon__totals')?.textContent).toContain('46,23 €');
    expect(el.querySelectorAll('.recon__rate').length).toBe(1);
    expect(el.querySelector('.recon__rate')?.textContent?.trim())
      .toBe('Montants convertis au taux 1 $ = 0,92 € ; la facture Anthropic est en dollars.');
    const rows = el.querySelectorAll('.recon__row');
    const row = rows[0] as HTMLElement;
    expect(row.textContent).toContain('73,60 €');
    expect(row.textContent).toContain('46,23 €');
    expect(row.textContent).not.toContain('$');
    expect(row.querySelector('.recon__gap')?.textContent).toContain('27,37 €');
    expect(rows[1].querySelector('.recon__gap')).toBeNull();
    expect(rows[1].textContent).toContain('0,55 €');
    expect(rows[2].textContent).toContain('—');
    expect(rows[2].textContent).toContain('1,84 €');
    (row.querySelector('.recon__toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.recon__key')?.textContent).toContain('legalcase (…owAA)');
    expect(el.querySelector('.recon__key')?.textContent).toContain('≈ 7,36 € (estimé à la grille)');
    // Hors de la mention, aucun dollar sur la carte.
    expect((el.querySelector('.recon')?.textContent ?? '').split('$').length - 1).toBe(1);
    (el.querySelector('.recon__refresh') as HTMLButtonElement).click();
    expect(service.refreshReconciliation).toHaveBeenCalled();
    recon = { ...recon, configured: false, setupHint: 'Créez une clé Admin… APP_ANTHROPIC_ADMIN_KEY', days: [] };
  });

  // ---------------------------------------- le budget par défaut, expliqué (SF-133-14)

  it('dit que le budget par défaut vaut PAR CLIENT, pas en enveloppe commune', () => {
    // LE CRITÈRE DE LA SUBFEATURE. Le PO avait lu « par défaut » comme un pot commun ; la règle
    // réelle applique le montant à chaque client. Un libellé ambigu sur un plafond de dépense est
    // un défaut, pas une question de style.
    setup();

    expect(text()).toContain('Budget hebdomadaire par client');
    expect(text()).toContain("Ce n'est pas une enveloppe commune");
  });

  it('écrit le plafond total qui en résulte, lu du résumé', () => {
    setup();

    // 120 € vient de `budgetEur` : l'écran ne le recalcule pas.
    expect(text()).toContain('120,00 € de plafond au total cette semaine');
    expect(text()).toContain('1 client budgété');
  });

  it("n'écrit aucun plafond total quand aucun budget n'est posé", () => {
    service = jasmine.createSpyObj<AdminCostService>('AdminCostService', [
      'summary', 'alerts', 'setDefaultBudget', 'setHostBudget', 'clearHostBudget', 'reconciliation',
    ]);
    service.reconciliation.and.returnValue(of(recon));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.summary.and.returnValue(of(summary({ budgetEur: null, percent: null })));
    service.alerts.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [AdminCostComponent, NoopAnimationsModule],
      providers: [
        { provide: AdminCostService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(AdminCostComponent);
    fixture.detectChanges();

    expect(text()).not.toContain('de plafond au total');
    // La règle, elle, reste écrite : elle vaut même sans budget posé.
    expect(text()).toContain("Ce n'est pas une enveloppe commune");
  });

  it('affiche le champ « Budget » pour un client qui n\'a rien dépensé', () => {
    // SF-133-13 : le résumé liste désormais tous les clients. L'écran doit leur proposer le champ,
    // sans quoi la correction backend resterait invisible.
    service = jasmine.createSpyObj<AdminCostService>('AdminCostService', [
      'summary', 'alerts', 'setDefaultBudget', 'setHostBudget', 'clearHostBudget', 'reconciliation',
    ]);
    service.reconciliation.and.returnValue(of(recon));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.summary.and.returnValue(of(summary({
      spentEur: 0,
      budgetEur: null,
      percent: null,
      clients: [{
        hostId: 'h9',
        hostName: 'poste-kg',
        spentEur: 0,
        budgetEur: null,
        percent: null,
        ownBudget: false,
        totalTokens: 0,
      }],
    })));
    service.alerts.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [AdminCostComponent, NoopAnimationsModule],
      providers: [
        { provide: AdminCostService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(AdminCostComponent);
    fixture.detectChanges();

    expect(text()).toContain('poste-kg');
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.cost-admin__client-actions').length)
      .toBe(1);
  });

  it('affiche le total et les clients en euros', () => {
    setup();

    expect(text()).toContain('47,80 €');
    expect(text()).toContain('poste-cagip');
    expect(text()).toContain('31,20 €');
    expect(text()).toContain('sur 60,00 €');
  });

  it('n\'affiche aucune part quand le client n\'a pas de budget', () => {
    // Une part inventée serait pire que pas de part du tout.
    service = jasmine.createSpyObj<AdminCostService>('AdminCostService', [
      'summary', 'alerts', 'setDefaultBudget', 'setHostBudget', 'clearHostBudget', 'reconciliation',
    ]);
    service.reconciliation.and.returnValue(of(recon));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.alerts.and.returnValue(of([]));
    service.summary.and.returnValue(of(summary({
      budgetEur: null,
      percent: null,
      clients: [{
        hostId: 'h1', hostName: 'poste-cagip', spentEur: 31.2, budgetEur: null, percent: null,
        ownBudget: false, totalTokens: 1000,
      }],
    })));
    TestBed.configureTestingModule({
      imports: [AdminCostComponent, NoopAnimationsModule],
      providers: [
        { provide: AdminCostService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(AdminCostComponent);
    fixture.detectChanges();

    expect(text()).toContain('Aucun budget');
    expect(text()).not.toContain('%');
  });

  it('recharge avec la bonne période quand on bascule', () => {
    setup();

    component.selectPeriod('month');

    expect(service.summary).toHaveBeenCalledWith('month');
    expect(component.period()).toBe('month');
  });

  it('ne recharge pas quand on choisit la période déjà affichée', () => {
    setup();
    service.summary.calls.reset();

    component.selectPeriod('week');

    expect(service.summary).not.toHaveBeenCalled();
  });

  it('refuse un budget négatif sans appeler la passerelle', () => {
    setup();
    component.defaultBudgetDraft.set('-10');

    component.saveDefaultBudget();

    expect(service.setDefaultBudget).not.toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('accepte la virgule française comme séparateur décimal', () => {
    setup();
    service.setDefaultBudget.and.returnValue(of({ defaultAmountEur: 12.5, hosts: [] }));
    component.defaultBudgetDraft.set('12,50');

    component.saveDefaultBudget();

    expect(service.setDefaultBudget).toHaveBeenCalledWith(12.5);
  });

  it('affiche un message quand la passerelle échoue, sans casser la section', () => {
    setup();
    service.summary.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })));

    component.reload();
    fixture.detectChanges();

    expect(component.error()).toContain('n’a pas pu être lue');
    expect(text()).toContain('Coût réel');
  });

  it('dit clairement qu\'une section réservée l\'est', () => {
    setup();
    service.summary.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 403 })));

    component.reload();

    expect(component.error()).toContain('administration');
  });

  it('borne la barre de progression à 100 même en cas de dépassement', () => {
    setup();

    expect(component.barValue(150)).toBe(100);
    expect(component.barValue(null)).toBe(0);
    expect(component.barValue(-5)).toBe(0);
  });

  it('nomme « Hors client » le seau qui n\'est pas un client', () => {
    setup();

    expect(component.clientName({
      hostId: null, hostName: null, spentEur: 1, budgetEur: null, percent: null,
      ownBudget: false, totalTokens: 1,
    })).toBe('Hors client');
  });
});
