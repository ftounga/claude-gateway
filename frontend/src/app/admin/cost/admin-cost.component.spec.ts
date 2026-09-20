import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AdminCostComponent } from './admin-cost.component';
import { AdminCostService } from './admin-cost.service';
import { AdminCostSummary } from './admin-cost.models';

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

  function setup(): void {
    service = jasmine.createSpyObj<AdminCostService>('AdminCostService', [
      'summary', 'alerts', 'setDefaultBudget', 'setHostBudget', 'clearHostBudget',
    ]);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.summary.and.returnValue(of(summary()));
    service.alerts.and.returnValue(of([]));

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
      'summary', 'alerts', 'setDefaultBudget', 'setHostBudget', 'clearHostBudget',
    ]);
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
      'summary', 'alerts', 'setDefaultBudget', 'setHostBudget', 'clearHostBudget',
    ]);
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
      'summary', 'alerts', 'setDefaultBudget', 'setHostBudget', 'clearHostBudget',
    ]);
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
