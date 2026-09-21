import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AdminCostSummary } from '../../admin/cost/admin-cost.models';
import { WeeklyBudgetService } from '../../core/services/weekly-budget.service';
import { WeeklyBudgetComponent } from './weekly-budget.component';

/**
 * Le budget de la semaine, là où l'on travaille (F-133 / SF-133-15).
 *
 * <p>Relevé qui a motivé la feature : deux budgets posés, 42 % consommés, et <b>rien nulle part</b>
 * — l'alerte ne parle qu'au-delà de 80 %, et le budget ne vivait que dans la console
 * d'administration. On avait livré l'alerte quand ça déborde, sans le compteur quand tout va
 * bien.</p>
 */
describe('WeeklyBudgetComponent', () => {
  @Component({
    imports: [WeeklyBudgetComponent],
    template: `<app-weekly-budget [hostId]="hostId" [compact]="compact" />`,
  })
  class HostComponent {
    hostId: string | null = 'h1';
    compact = false;
  }

  let fixture: ComponentFixture<HostComponent>;
  let http: HttpTestingController;

  const URL = '/api/admin/cost/summary?period=week';

  function summary(spentEur: number, budgetEur: number | null, percent: number | null): AdminCostSummary {
    return {
      period: 'week',
      from: '2026-09-21',
      to: '2026-09-27',
      spentEur,
      budgetEur,
      percent,
      clients: [
        {
          hostId: 'h1',
          hostName: 'CAGIP',
          spentEur,
          budgetEur,
          percent,
          ownBudget: true,
          totalTokens: 1000,
        },
      ],
    };
  }

  function setup(answer: AdminCostSummary | null, status = 200): void {
    TestBed.inject(WeeklyBudgetService).load();
    const request = http.expectOne(URL);
    if (status === 200 && answer) {
      request.flush(answer);
    } else {
      request.flush('non', { status, statusText: 'Forbidden' });
    }
    fixture.detectChanges();
  }

  function dom(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(HostComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('LE CRITÈRE : dit où l\'on en est AVANT de déborder', () => {
    // 42 % — le cas réel du 2026-09-22, celui où rien ne s'affichait nulle part.
    setup(summary(41.92, 100, 42));

    expect(dom().textContent).toContain('Cette semaine : 41,92 € sur 100,00 €');
    expect(dom().querySelector('.weekly-budget--near')).toBeNull();
    expect(dom().querySelector('.weekly-budget--over')).toBeNull();
  });

  it('prend les couleurs de l\'alerte à l\'approche, puis au dépassement', () => {
    // Mêmes seuils que SF-133-06 : deux écrans qui diraient deux choses du même budget seraient
    // pires qu'un seul écran.
    setup(summary(85, 100, 85));
    expect(dom().querySelector('.weekly-budget--near')).not.toBeNull();

    TestBed.inject(WeeklyBudgetService).summary.set(summary(120, 100, 120));
    fixture.detectChanges();
    expect(dom().querySelector('.weekly-budget--over')).not.toBeNull();
  });

  it('la jauge ne déborde jamais de son cadre', () => {
    setup(summary(300, 100, 300));

    const fill = dom().querySelector('.weekly-budget__fill') as HTMLElement;
    expect(fill.style.width).toBe('100%');
    // Mais le texte, lui, dit la vérité.
    expect(dom().textContent).toContain('300,00 € sur 100,00 €');
  });

  it('n\'affiche RIEN sans budget applicable : on n\'invente pas un plafond', () => {
    setup(summary(41.92, null, null));

    expect(dom().querySelector('.weekly-budget')).toBeNull();
  });

  it('n\'affiche rien quand la lecture est refusée, et ne casse pas l\'écran', () => {
    // 403 : le montant ne doit pas quitter le serveur pour qui n'est pas administrateur.
    setup(null, 403);

    expect(dom().querySelector('.weekly-budget')).toBeNull();
  });

  it('resserre la phrase pour la barre du terminal', () => {
    fixture.componentInstance.compact = true;
    setup(summary(41.92, 100, 42));

    expect(dom().textContent).toContain('41,92 € / 100,00 €');
    expect(dom().textContent).not.toContain('Cette semaine');
  });

  it('ne lit qu\'UNE fois, même si les deux écrans le demandent', () => {
    const service = TestBed.inject(WeeklyBudgetService);
    service.load();
    http.expectOne(URL).flush(summary(41.92, 100, 42));

    // Le terminal demande à son tour : aucune seconde requête, donc aucun risque que les deux
    // écrans affichent deux chiffres du même instant.
    service.load();
    http.expectNone(URL);
  });
});
