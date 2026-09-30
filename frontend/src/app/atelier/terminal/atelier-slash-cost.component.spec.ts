import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierSlashCostComponent } from './atelier-slash-cost.component';
import { ThreadCostSummary } from './slash-panel-commands';

/**
 * Le corps du panneau /cout (F-165 / SF-165-02) : présentation pure. On vérifie les trois états
 * (chargement / échec / prêt), le rendu des chiffres et des parts, et l'affichage conditionnel du
 * budget.
 */
describe('AtelierSlashCostComponent (F-165 / SF-165-02)', () => {
  let fixture: ComponentFixture<AtelierSlashCostComponent>;
  let component: AtelierSlashCostComponent;

  const summary: ThreadCostSummary = {
    currency: 'EUR', cumulativeEur: 0.46, lastTurnEur: 0.12, turnCount: 3,
    breakdown: {
      writeEur: 0.08, writePercent: 49, readEur: 0.04, readPercent: 24,
      outputEur: 0.05, outputPercent: 27,
    },
    hotCachePercent: 90, contextTokens: 100000, contextPages: 200,
    liveTurns: 3, foldedTurns: 5, trendEur: [0.1, 0.2, 0.46],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierSlashCostComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashCostComponent);
    component = fixture.componentInstance;
  });

  it('affiche un état de chargement sans planter', () => {
    component.state = 'loading';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.cost-note')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.cost-figures')).toBeNull();
  });

  it('affiche un état d\'échec neutre', () => {
    component.state = 'error';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.cost-note--error')).not.toBeNull();
  });

  it('rend les montants, les parts et le contexte en état « ready »', () => {
    component.state = 'ready';
    component.cost = summary;
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('0,46 €'); // coût du fil
    expect(text).toContain('49 %'); // part écriture
    expect(text).toContain('90 %'); // cache chaud
    expect(text).toContain('200'); // pages vivantes
    // Trois segments de barre pour la décomposition.
    expect(fixture.nativeElement.querySelectorAll('.cost-bar__seg').length).toBe(3);
    // Une mini-tendance à trois barres.
    expect(fixture.nativeElement.querySelectorAll('.cost-trend__bar').length).toBe(3);
    // Aucun budget fourni : la ligne est absente (pas de plafond inventé).
    expect(fixture.nativeElement.querySelector('.cost-budget')).toBeNull();
  });

  it('affiche le budget restant seulement quand un budget est fourni', () => {
    component.state = 'ready';
    component.cost = summary;
    component.budget = { hostName: 'ACME', budgetEur: 10, spentEur: 6 };
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.cost-budget')).not.toBeNull();
    expect(component.budgetRemaining).toBe(4);
    expect(component.budgetPercent).toBe(60);
    expect(fixture.nativeElement.textContent).toContain('ACME');
  });
});
