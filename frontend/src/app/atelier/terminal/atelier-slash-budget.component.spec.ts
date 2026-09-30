import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierSlashBudgetComponent } from './atelier-slash-budget.component';
import { WeeklyBudgetService } from '../../core/services/weekly-budget.service';
import { AdminCostClient } from '../../admin/cost/admin-cost.models';

/**
 * Le corps du panneau /budget (F-165 / SF-165-04) : présentation pure au-dessus du service PARTAGÉ
 * WeeklyBudgetService. On vérifie le rendu quand le budget est lisible, et la DÉGRADATION propre (jamais
 * le budget d'autrui) quand il ne l'est pas.
 */
describe('AtelierSlashBudgetComponent (F-165 / SF-165-04)', () => {
  let fixture: ComponentFixture<AtelierSlashBudgetComponent>;
  let loadSpy: jasmine.Spy;

  function configure(clientOf: (hostId: string | null) => AdminCostClient | null): void {
    loadSpy = jasmine.createSpy('load');
    TestBed.configureTestingModule({
      imports: [AtelierSlashBudgetComponent, NoopAnimationsModule],
      providers: [
        { provide: WeeklyBudgetService, useValue: { load: loadSpy, clientOf } },
      ],
    });
    fixture = TestBed.createComponent(AtelierSlashBudgetComponent);
  }

  it('rend le budget quand il est lisible, et déclenche la lecture partagée (idempotente)', () => {
    const line: AdminCostClient = {
      hostId: 'h1', hostName: 'ACME', spentEur: 6, budgetEur: 10, percent: 60,
      ownBudget: true, totalTokens: 1000,
    };
    configure((hostId) => (hostId === 'h1' ? line : null));
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.detectChanges();

    expect(loadSpy).toHaveBeenCalled();
    const component = fixture.componentInstance;
    expect(component.remaining()).toBe(4);
    expect(component.percent()).toBe(60);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('ACME');
    expect(text).toContain('4,00 €');
    expect(fixture.nativeElement.querySelector('.budget-bar__seg')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.budget-note')).toBeNull();
  });

  it('dégrade proprement quand le budget n\'est pas lisible (non-admin ou aucun plafond)', () => {
    configure(() => null);
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.detectChanges();

    // Aucun montant d'autrui : seulement le message neutre.
    expect(fixture.nativeElement.querySelector('.budget-figures')).toBeNull();
    const note = fixture.nativeElement.querySelector('.budget-note');
    expect(note).not.toBeNull();
    expect(note.textContent).toContain('administrateurs');
  });

  it('dégrade aussi quand il n\'y a pas de poste (hostId null)', () => {
    configure((hostId) => (hostId ? ({} as AdminCostClient) : null));
    fixture.componentRef.setInput('hostId', null);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.budget-note')).not.toBeNull();
  });
});
