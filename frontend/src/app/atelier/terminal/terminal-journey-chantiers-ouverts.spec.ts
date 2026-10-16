import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { TerminalJourneyStripComponent } from './terminal-journey-strip.component';
import { JourneyService } from '../../core/services/journey.service';
import {
  OpenChantier, SubjectJourney, focusChantierNumber, journeyResumeMessage,
} from '../../core/models/journey.models';

const chantier = (number: number, focus: boolean, extra: Partial<OpenChantier> = {}): OpenChantier => ({
  number, title: `Chantier ${number}`, openedAt: null, phase: 'EXECUTION', phaseLabel: 'Exécution', focus,
  planVersion: 1, validatedVersion: 1, awaitingValidation: false, diagnosisPending: false, closeProposed: false,
  executing: true, done: 0, total: 1, ...extra,
});

const twoOpen = (): SubjectJourney => ({
  mode: 'GUIDE', phase: 'PLAN', phaseLabel: 'Plan', phaseChangedAt: null,
  chantier: { number: 2, title: 'Migration DNS', openedAt: null },
  plan: {
    version: 1, validatedVersion: null, validatedAt: null, awaitingValidation: true, amendment: false,
    waitingInputs: 0, steps: [],
  },
  chantiers: [
    chantier(1, false, { phase: 'PLAN', phaseLabel: 'Plan', awaitingValidation: true, executing: false }),
    chantier(2, true, { phase: 'PLAN', phaseLabel: 'Plan' }),
  ],
});

/** F-206 / SF-206-04 — plusieurs chantiers ouverts à l'écran. */
describe('F-206 — chantiers ouverts dans la bande du parcours', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
  });

  it('deux chantiers ouverts : un onglet chacun, le focus marqué, l’autre bascule', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', twoOpen());
    fixture.detectChanges();
    const tabs = fixture.nativeElement.querySelectorAll('.journey-chantier') as NodeListOf<HTMLButtonElement>;
    expect(tabs.length).toBe(2);
    expect(tabs[0].textContent).toContain('Chantier 1 · Plan');
    expect(tabs[0].textContent).toContain('à vous');
    expect(tabs[1].getAttribute('aria-pressed')).toBe('true');
    const switched: number[] = [];
    fixture.componentInstance.switchChantier.subscribe(n => switched.push(n));
    tabs[1].click();
    tabs[0].click();
    expect(switched).toEqual([1]);
  });

  it('les cartes disent de quel chantier elles parlent', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    fixture.componentRef.setInput('journey', twoOpen());
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.journey-plan__toggle').textContent).toContain('Chantier 2 · Plan v1');
  });

  it('un seul chantier : ni onglet ni numéro sur les cartes', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    const single = { ...twoOpen(), chantiers: [chantier(2, true)] };
    fixture.componentRef.setInput('journey', single);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.journey-chantiers')).toBeNull();
    expect(fixture.nativeElement.querySelector('.journey-plan__toggle').textContent).not.toContain('Chantier');
  });

  it('la proposition d’un chantier de plus s’affiche sur un sujet guidé', () => {
    const fixture = TestBed.createComponent(TerminalJourneyStripComponent);
    const journey: SubjectJourney = {
      ...twoOpen(), guidedProposal: { reason: 'Migration DNS distincte', proposedAt: '2026-10-16T08:00:00Z', newChantier: true },
    };
    fixture.componentRef.setInput('journey', journey);
    fixture.detectChanges();
    const card = fixture.nativeElement.querySelector('.journey-card') as HTMLElement;
    expect(card.textContent).toContain('Cette demande ouvre un chantier de plus.');
    expect(card.textContent).toContain('Ouvrir ce chantier et continuer');
    expect(card.textContent).toContain('Pas maintenant');
    expect(journeyResumeMessage('accept-guided', journey)).toBe('✓ Nouveau chantier ouvert — investigation lancée.');
  });

  it('le focus du parcours en direct, sinon rien', () => {
    expect(focusChantierNumber(twoOpen())).toBe(2);
    expect(focusChantierNumber({ ...twoOpen(), mode: 'LIBRE' })).toBeNull();
    expect(focusChantierNumber(null)).toBeNull();
  });

  it('le service bascule et ouvre un chantier sans identifiant de compte', () => {
    const service = TestBed.inject(JourneyService);
    const http = TestBed.inject(HttpTestingController);
    service.focus('w1', 1).subscribe();
    const focus = http.expectOne('/api/workspaces/w1/journey/chantiers/1/focus');
    expect(focus.request.method).toBe('POST');
    expect(focus.request.body).toEqual({});
    focus.flush(twoOpen());
    service.openChantier('w1', 'DNS').subscribe();
    const open = http.expectOne('/api/workspaces/w1/journey/chantiers');
    expect(open.request.body).toEqual({ title: 'DNS' });
    open.flush(twoOpen());
    http.verify();
  });
});
