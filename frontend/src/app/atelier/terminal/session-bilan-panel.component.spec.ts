import { ComponentFixture, TestBed } from '@angular/core/testing';

import {
  SessionBilanPanelComponent,
  axisLabel,
  elapsedLabel,
} from './session-bilan-panel.component';
import { AtelierBilanReport } from '../../core/models/atelier.models';

/** Les chiffres réels de la session KPMG — ceux que le PO n'a pas pu lire le 2026-09-26. */
function kpmg(overrides: Partial<AtelierBilanReport> = {}): AtelierBilanReport {
  return {
    kept: true,
    workspaceName: 'agenor',
    turns: 113,
    elapsedMinutes: 936,
    costEur: 152.58,
    cacheShare: 86,
    toolCalls: 412,
    failedTools: 31,
    filesWritten: 47,
    model: 'claude-opus-5',
    discarded: 1,
    suggestions: [
      {
        kind: 'OUTIL_DOMINANT',
        axis: 'TEMPS',
        advice: "bash concentre l'attente de la session.",
        measure: "61 % du temps d'outils sur 412 appels.",
        gainPct: 22,
        gainEur: null,
      },
      {
        kind: 'ECHECS_REPETES',
        axis: 'COUT',
        advice: 'Le poste a décroché plusieurs fois.',
        measure: "31 appels d'outils en échec sur 412.",
        gainPct: 11,
        gainEur: 9.1,
      },
    ],
    ...overrides,
  };
}

describe('SessionBilanPanelComponent (F-155 / SF-155-07)', () => {

  let fixture: ComponentFixture<SessionBilanPanelComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SessionBilanPanelComponent],
    }).compileComponents();
  });

  /** Le module est configuré UNE fois par test ; seule la fixture se recrée. */
  function render(report: AtelierBilanReport): HTMLElement {
    fixture = TestBed.createComponent(SessionBilanPanelComponent);
    fixture.componentRef.setInput('report', report);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => fixture?.destroy());

  it('montre les chiffres de la session — ce que le bandeau ne disait pas', () => {
    const el = render(kpmg());

    const text = el.textContent ?? '';
    expect(text).toContain('152.58');
    expect(text).toContain('113');
    expect(text).toContain('86 %');
    expect(text).toContain('15 h 36 min');
  });

  it('rend CHAQUE suggestion avec sa mesure — sans elle, ce ne serait qu\'un avis', () => {
    const el = render(kpmg());

    const suggestions = el.querySelectorAll('.suggestion');
    expect(suggestions.length).toBe(2);
    const text = el.textContent ?? '';
    expect(text).toContain("61 % du temps d'outils sur 412 appels.");
    expect(text).toContain("31 appels d'outils en échec sur 412.");
    expect(text).toContain('9.10');
  });

  it('dit le nombre de suggestions écartées — pour ne pas croire que rien n\'a été cherché', () => {
    const el = render(kpmg());

    expect(el.textContent).toContain('1 suggestion(s) écartée(s)');
  });

  it('distingue un bilan gardé d\'un bilan seulement proposé', () => {
    expect(render(kpmg({ kept: true })).textContent).toContain('gardée');
    fixture.destroy();

    expect(render(kpmg({ kept: false })).textContent).toContain("n'est pas gardé");
  });

  it('une session sans rien à signaler le dit, au lieu d\'une liste vide', () => {
    const el = render(kpmg({ suggestions: [], discarded: 0 }));

    expect(el.querySelectorAll('.suggestion').length).toBe(0);
    expect(el.textContent).toContain('Rien à signaler');
  });

  it('ferme sur Échap — un panneau, pas une route', () => {
    render(kpmg());
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => (closed += 1));

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(closed).toBe(1);
  });

  describe('elapsedLabel', () => {
    it('reste en minutes sous l\'heure, et passe en heures au-delà', () => {
      expect(elapsedLabel(0)).toBe('0 min');
      expect(elapsedLabel(42)).toBe('42 min');
      expect(elapsedLabel(60)).toBe('1 h');
      expect(elapsedLabel(936)).toBe('15 h 36 min');
    });
  });

  describe('axisLabel', () => {
    it('dit les axes en français, et laisse passer l\'inconnu sans mentir', () => {
      expect(axisLabel('COUT')).toBe('Coût');
      expect(axisLabel('TEMPS')).toBe('Temps');
      expect(axisLabel('RAISONNEMENT')).toBe('Raisonnement');
      expect(axisLabel('AUTRE_CHOSE')).toBe('AUTRE_CHOSE');
    });
  });
});
