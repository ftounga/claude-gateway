import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent, ParallelLaunchRequest } from './atelier-terminal.component';
import { TerminalChantiersBoardComponent } from './terminal-chantiers-board.component';
import { TerminalNextStepsComponent } from './terminal-next-steps.component';
import { OpenChantier, SubjectJourney } from '../../core/models/journey.models';
import { Unblocker } from '../../core/models/client-board.models';

const mark = (who: Unblocker, detail = 'd') => ({ who, label: who, detail, step: null, waitingOn: null, since: null });

const chantier = (number: number, who: Unblocker, extra: Partial<OpenChantier> = {}): OpenChantier => ({
  number, title: `Titre ${number}`, openedAt: null, phase: 'EXECUTION', phaseLabel: 'Exécution', focus: false,
  planVersion: 2, validatedVersion: 2, awaitingValidation: false, diagnosisPending: false, closeProposed: false,
  executing: true, done: 0, total: 2, plan: null, unblocker: mark(who), ...extra,
});

const journey = (chantiers: OpenChantier[], focus: number): SubjectJourney => ({
  mode: 'GUIDE', phase: 'EXECUTION', phaseLabel: 'Exécution', phaseChangedAt: null, chantiers,
  chantier: { number: focus, title: null, openedAt: null },
}) as SubjectJourney;

/** F-226 / SF-226-01 — suggérer le chantier parallèle, et trois verbes. */
describe('F-226 / SF-226-01 — la suggestion du chantier parallèle', () => {
  let component: AtelierTerminalComponent;
  let launched: ParallelLaunchRequest[];

  beforeEach(() => {
    try {
      localStorage.removeItem('cg.suggestParallel.off');
    } catch {
      // sans stockage
    }
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
    });
    const fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectId = 'ws-1';
    launched = [];
    component.parallelLaunch.subscribe((r) => launched.push(r));
  });

  afterEach(() => TestBed.inject(HttpTestingController).match(() => true));

  it('au moment creux (le chantier affiché vous attend), un autre que l’outil peut faire avancer est suggéré', () => {
    component.journey.set(journey([chantier(1, 'MOI', { focus: true }), chantier(2, 'OUTIL'), chantier(3, 'AUTRUI')], 1));
    expect(component.suggestion()).toEqual({ number: 2, title: 'Titre 2' });
    component.launchAside(2, 'suggestion');
    expect(launched).toEqual([{ number: 2, mode: null, source: 'suggestion' }]);
  });

  it('pas de suggestion quand l’outil avance sur le chantier affiché (pas un moment creux)', () => {
    component.journey.set(journey([chantier(1, 'OUTIL', { focus: true }), chantier(2, 'OUTIL')], 1));
    expect(component.suggestion()).toBeNull();
  });

  it('juste après « Valider et lancer », c’est un moment creux', () => {
    component.journey.set(journey([chantier(1, 'OUTIL', { focus: true }), chantier(2, 'OUTIL')], 1));
    component.justLaunched.set(true);
    expect(component.suggestion()?.number).toBe(2);
  });

  it('✕ masque jusqu’à ce que l’état du chantier change ; un chantier qui tourne ou 4 courses : rien', () => {
    component.journey.set(journey([chantier(1, 'MOI', { focus: true }), chantier(2, 'OUTIL')], 1));
    component.dismissSuggestion(2);
    expect(component.suggestion()).toBeNull();
    component.journey.set(journey([chantier(1, 'MOI', { focus: true }), chantier(2, 'OUTIL', { done: 1 })], 1));
    expect(component.suggestion()?.number).toBe(2);
    component.activeLanes = [2];
    expect(component.suggestion()).toBeNull();
    component.activeLanes = [5, 6, 7, 8];
    expect(component.suggestion()).toBeNull();
  });

  it('coupée dans le menu, elle se tait ; en lecture seule aussi', () => {
    component.journey.set(journey([chantier(1, 'MOI', { focus: true }), chantier(2, 'OUTIL')], 1));
    component.toggleSuggestions();
    expect(component.suggestion()).toBeNull();
    component.toggleSuggestions();
    expect(component.suggestion()?.number).toBe(2);
    component.readOnly = true;
    expect(component.suggestion()).toBeNull();
  });
});

describe('F-226 / SF-226-01 — les gestes du tableau et des suites', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.match(() => true));

  it('[Valider le plan] sur la carte d’un chantier dont le plan attend : par numéro, focus inchangé', () => {
    const fixture = TestBed.createComponent(TerminalChantiersBoardComponent);
    fixture.componentRef.setInput('journey', journey([chantier(1, 'MOI', { focus: true }),
      chantier(3, 'MOI', { awaitingValidation: true, planVersion: 4, validatedVersion: null })], 1));
    fixture.componentRef.setInput('workspaceId', 'w1');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    (Array.from(el.querySelectorAll('.board__tab')) as HTMLButtonElement[])[1].click();
    fixture.detectChanges();
    expect(el.querySelector('.board__focus-btn')?.textContent).toContain('Afficher');
    (el.querySelector('.board__validate-btn') as HTMLButtonElement).click();
    const req = http.expectOne('/api/workspaces/w1/journey/chantiers/3/plan/validate');
    expect(req.request.body).toEqual({ version: 4 });
  });

  it('une suite vers un chantier qui peut avancer seul : « Lancer à côté » ; sinon « Afficher le chantier N »', () => {
    const fixture = TestBed.createComponent(TerminalNextStepsComponent);
    const asides: number[] = [];
    const shows: number[] = [];
    fixture.componentInstance.launchAside.subscribe((n) => asides.push(n));
    fixture.componentInstance.switchChantier.subscribe((n) => shows.push(n));
    fixture.componentRef.setInput('steps', [
      { title: 'A', why: 'w', label: 'CONTINUER', request: 'a', scope: 'CHANTIER', chantier: 2 },
      { title: 'B', why: 'w', label: 'CONTINUER', request: 'b', scope: 'CHANTIER', chantier: 3 },
    ]);
    fixture.componentRef.setInput('chantier', { number: 1, done: 0, total: 2,
      others: [{ number: 2, done: 0, total: 2 }, { number: 3, done: 0, total: 2 }] });
    fixture.componentRef.setInput('turnKey', 'm1');
    fixture.componentRef.setInput('focus', 1);
    fixture.componentRef.setInput('asideCapable', [2]);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const aside = el.querySelector('.switch--aside') as HTMLButtonElement | null;
    const plain = Array.from(el.querySelectorAll('.switch:not(.switch--aside)')) as HTMLButtonElement[];
    expect(aside).not.toBeNull();
    expect(plain.length).toBe(1);
    if (aside) {
      expect(aside.textContent).toContain('Lancer à côté le chantier 2');
      aside.click();
      expect(asides).toEqual([2]);
    }
    if (plain.length > 0) {
      expect(plain[0].textContent).toContain('Afficher le chantier');
      plain[0].click();
      expect(shows.length).toBe(1);
    }
    expect(fixture.componentInstance.canAdvanceAlone(2)).toBeTrue();
    expect(fixture.componentInstance.canAdvanceAlone(3)).toBeFalse();
  });
});
