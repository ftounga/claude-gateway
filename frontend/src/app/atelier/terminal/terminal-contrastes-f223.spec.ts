import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { TerminalJourneyStripComponent } from './terminal-journey-strip.component';
import { TerminalChantiersBoardComponent } from './terminal-chantiers-board.component';
import { JourneyPlanStep, OpenChantier, SubjectJourney } from '../../core/models/journey.models';

/** Luminance relative WCAG d'une couleur CSS calculée (`rgb(…)` / `rgba(…)`). */
function luminance(css: string): number {
  const [r, g, b] = (css.match(/[\d.]+/g) ?? ['0', '0', '0']).slice(0, 3).map(Number).map((c) => {
    const v = c / 255;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

const NAVY = 'rgb(26, 58, 92)'; // --cg-primary, le fond du terminal
const WHITE = 'rgb(255, 255, 255)';

const step = (title: string, extra: Partial<JourneyPlanStep> = {}): JourneyPlanStep => ({
  title, risk: 'REVERSIBLE', riskLabel: 'modification réversible', verify: 'curl ok', rollback: 'revert', waitsOn: null,
  waitsOnStatus: null, status: 'A_FAIRE', evidence: null, changed: false, dependsOn: [], unlocked: true,
  blockedBy: [], inProgress: false, subSteps: [], ...extra,
});

/**
 * **F-223 / SF-223-02 — les contrastes du terminal sombre, mesurés sur les couleurs RENDUES** (styles globaux
 * compris) : seuil AA 4,5:1. Avant : titres 1,24, secondaire 2,66, externe 2,34, « fait » 4,05, boutons du
 * tableau ≈ 1,0.
 */
describe('F-223 / SF-223-02 — contrastes du terminal', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
  });

  it('la bande du parcours : titres, secondaire, « fait », externe ≥ 4,5:1 sur le navy', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', {
      mode: 'GUIDE', phase: 'PLAN', phaseLabel: 'Plan', phaseChangedAt: null,
      plan: {
        version: 2, validatedVersion: 1, validatedAt: null, awaitingValidation: true, amendment: true, waitingInputs: 0,
        steps: [step('Lire', { status: 'FAIT' }), step('Appliquer', { risk: 'EXTERNE', riskLabel: 'externe' })],
      },
    } as unknown as SubjectJourney);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    // Le terminal sombre : un ancêtre navy, sans encre définie (l'héritage par défaut est l'encre des écrans clairs).
    el.style.background = NAVY;
    document.body.appendChild(el);
    const color = (selector: string) => getComputedStyle(el.querySelector(selector) as Element).color;
    expect(contrast(color('.journey-plan__title'), NAVY)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(color('.journey-plan__toggle'), NAVY)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(color('.journey-step'), NAVY)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(color('.journey-plan__detail'), NAVY)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(color(".journey-plan__status[data-status='FAIT']"), NAVY)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(color(".journey-plan__risk[data-risk='EXTERNE']"), NAVY)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(color('.journey-plan__hint'), NAVY)).toBeGreaterThanOrEqual(4.5);
    el.remove();
  });

  it('le tableau des chantiers est un îlot clair : ses boutons ne prennent plus l\'encre claire du terminal', () => {
    const wrapper = document.createElement('div');
    // Ce que le terminal sombre pose sur ses descendants (atelier-terminal.component.scss).
    wrapper.style.setProperty('--mdc-outlined-button-label-text-color', '#FFFFFF');
    wrapper.style.setProperty('--mdc-text-button-label-text-color', '#E0E4EA');
    document.body.appendChild(wrapper);
    const fixture = TestBed.createComponent(TerminalChantiersBoardComponent);
    const c: OpenChantier = {
      number: 1, title: 'Migration', openedAt: null, phase: 'EXECUTION', phaseLabel: 'Exécution', focus: false,
      planVersion: 3, validatedVersion: 3, awaitingValidation: false, diagnosisPending: false, closeProposed: false,
      executing: true, done: 0, total: 1,
      plan: { version: 3, validatedVersion: 3, validatedAt: null, awaitingValidation: false, amendment: false, waitingInputs: 0,
        steps: [step('Basculer')] },
    } as OpenChantier;
    fixture.componentRef.setInput('journey', {
      mode: 'GUIDE', phase: 'EXECUTION', phaseLabel: 'Exécution', phaseChangedAt: null, chantiers: [c],
      chantier: { number: 2, title: null, openedAt: null },
    } as unknown as SubjectJourney);
    fixture.componentRef.setInput('workspaceId', 'w1');
    wrapper.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.board button.mat-mdc-outlined-button'));
    expect(buttons.length).toBeGreaterThan(0);
    for (const b of buttons) {
      const label = getComputedStyle(b).color;
      expect(contrast(label, WHITE)).withContext((b.textContent ?? '').trim()).toBeGreaterThanOrEqual(4.5);
    }
    TestBed.inject(HttpTestingController).match(() => true);
    wrapper.remove();
  });
});
