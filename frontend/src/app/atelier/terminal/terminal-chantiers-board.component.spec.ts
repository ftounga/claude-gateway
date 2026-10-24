import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { TerminalChantiersBoardComponent } from './terminal-chantiers-board.component';
import {
  JourneyPlan, JourneyPlanStep, OpenChantier, SubjectJourney, chantiersGestures, currentStepIndex, stepState,
} from '../../core/models/journey.models';
import { JourneyService } from '../../core/services/journey.service';
import { AtelierService } from '../../core/services/atelier.service';

const step = (title: string, extra: Partial<JourneyPlanStep> = {}): JourneyPlanStep => ({
  title, risk: 'REVERSIBLE', riskLabel: 'modification réversible', verify: null, rollback: null, waitsOn: null,
  waitsOnStatus: null, status: 'A_FAIRE', evidence: null, changed: false, dependsOn: [], unlocked: true,
  blockedBy: [], inProgress: false, subSteps: [], ...extra,
});

const plan = (steps: JourneyPlanStep[], extra: Partial<JourneyPlan> = {}): JourneyPlan => ({
  version: 3, validatedVersion: 3, validatedAt: null, awaitingValidation: false, amendment: false,
  waitingInputs: 0, steps, ...extra,
});

const chantier = (number: number, extra: Partial<OpenChantier> = {}): OpenChantier => ({
  number, title: `Titre ${number}`, openedAt: null, phase: 'EXECUTION', phaseLabel: 'Exécution', focus: false,
  planVersion: 3, validatedVersion: 3, awaitingValidation: false, diagnosisPending: false, closeProposed: false,
  executing: true, done: 0, total: 0, plan: null, ...extra,
});

const MIGRATION = plan([
  step('Lire la config', { status: 'VERIFIE', evidence: 'cat ok', risk: 'LECTURE', riskLabel: 'lecture' }),
  step('Sauvegarder la base', {
    inProgress: true, verify: 'dump présent', rollback: 'rien',
    subSteps: [{ title: 'pg_dump', done: true, evidence: '12 Mo' }, { title: 'copie S3', done: false, evidence: null }],
  }),
  step('Basculer le DNS', { risk: 'EXTERNE', riskLabel: 'externe', unlocked: false, blockedBy: ['étape 2', 'attente cert-gino'], dependsOn: [2] }),
  step('Annoncer', { status: 'ECHEC', evidence: 'mail rejeté' }),
  step('Nettoyer', { status: 'FAIT' }),
]);

const journey = (chantiers: OpenChantier[]): SubjectJourney => ({
  mode: 'GUIDE', phase: 'EXECUTION', phaseLabel: 'Exécution', phaseChangedAt: null, chantiers,
  chantier: { number: chantiers.find((c) => c.focus)?.number ?? 1, title: null, openedAt: null },
}) as SubjectJourney;

/** **Le tableau des chantiers** (F-208 / SF-208-03). */
describe('F-208 / SF-208-03 — le tableau des chantiers', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const mount = (j: SubjectJourney | null) => {
    const fixture = TestBed.createComponent(TerminalChantiersBoardComponent);
    fixture.componentRef.setInput('journey', j);
    fixture.componentRef.setInput('workspaceId', 'w1');
    fixture.detectChanges();
    return fixture;
  };

  it('fonctions pures : état affiché, étape en cours, gestes attendus', () => {
    expect(MIGRATION.steps.map(stepState)).toEqual(['verified', 'progress', 'blocked', 'failed', 'done']);
    expect(currentStepIndex(MIGRATION)).toBe(1);
    expect(currentStepIndex(plan([step('a', { status: 'FAIT' }), step('b')]))).toBe(1);
    expect(currentStepIndex(plan([step('a', { status: 'FAIT' })]))).toBe(-1);
    expect(chantiersGestures(journey([
      chantier(1, { awaitingValidation: true, plan: plan([], { waitingInputs: 2 }) }),
      chantier(2, { closeProposed: true, diagnosisPending: true }),
    ]))).toBe(5);
    expect(chantiersGestures(null)).toBe(0);
  });

  it('onglets : le focus d’abord, avec « à vous » sur celui qui attend un geste', () => {
    const fixture = mount(journey([
      chantier(1, { awaitingValidation: true }),
      chantier(4, { focus: true, plan: MIGRATION, done: 2, total: 5 }),
    ]));
    const el: HTMLElement = fixture.nativeElement;
    const tabs = Array.from(el.querySelectorAll('.board__tab')).map((t) => t.textContent ?? '');
    expect(tabs[0]).toContain('Ch.4');
    expect(tabs[0]).toContain('focus');
    expect(tabs[1]).toContain('Ch.1');
    expect(tabs[1]).toContain('à vous');
    expect(el.querySelector('.board__chantier-title')?.textContent).toContain('Chantier 4 — Titre 4');
    expect(el.querySelector('.board__count')?.textContent).toContain('2/5');
    expect(el.querySelector('.board__count')?.textContent).toContain('plan v3 · validé');
    expect(el.querySelector('.board__phase[data-state="current"]')?.textContent).toContain('Exécution');
    expect(el.querySelectorAll('.board__phase[data-state="past"]').length).toBe(2);
    expect(el.querySelector('.board__ring-value')?.getAttribute('stroke-dasharray')).toMatch(/^45\.2 113\.1$/);
  });

  it('toutes les étapes, chacune avec son état ; une étape bloquée dit ce qui la bloque', () => {
    const fixture = mount(journey([chantier(4, { focus: true, plan: MIGRATION, done: 2, total: 5 })]));
    const el: HTMLElement = fixture.nativeElement;
    const steps = Array.from(el.querySelectorAll<HTMLElement>('.board__step'));
    expect(steps.length).toBe(5);
    expect(steps.map((s) => s.dataset['state'])).toEqual(['verified', 'progress', 'blocked', 'failed', 'done']);
    expect(steps.map((s) => s.querySelector('.board__step-state')?.textContent?.trim()))
      .toEqual(['vérifiée', 'en cours', 'bloquée', 'échec', 'faite']);
    expect(steps[2].querySelector('.board__blocked')?.textContent).toContain('après l’étape 2 · attente cert-gino');
    expect(steps[1].querySelector('.board__subcount')?.textContent).toContain('sous-étapes 1/2');
  });

  it('l’étape en cours est dépliée d’office ; une autre se déplie au clic', () => {
    const fixture = mount(journey([chantier(4, { focus: true, plan: MIGRATION })]));
    const el: HTMLElement = fixture.nativeElement;
    const steps = () => Array.from(el.querySelectorAll<HTMLElement>('.board__step'));
    expect(steps()[1].querySelector('.board__detail')?.textContent).toContain('pg_dump');
    expect(steps()[1].querySelector('.board__detail')?.textContent).toContain('12 Mo');
    expect(steps()[1].querySelector('.board__detail')?.textContent).toContain('dump présent');
    expect(steps()[0].querySelector('.board__detail')).toBeNull();
    (steps()[0].querySelector('.board__step-head') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(steps()[0].querySelector('.board__detail')?.textContent).toContain('cat ok');
    (steps()[1].querySelector('.board__step-head') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(steps()[1].querySelector('.board__detail')).toBeNull();
  });

  it('l’historique se lit à la demande : ajouts, retraits, modifications ; vide et erreur le disent', () => {
    const fixture = mount(journey([chantier(4, { focus: true, plan: MIGRATION })]));
    const el: HTMLElement = fixture.nativeElement;
    expect(http.match(() => true).length).toBe(0);
    (el.querySelector('.board__history-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectOne('/api/workspaces/w1/journey/chantiers/4/versions').flush([
      { version: 1, createdAt: '2026-10-24T08:00:00Z', stepCount: 1, added: ['Lire la config'], removed: [], modified: [] },
      { version: 2, createdAt: '2026-10-24T09:00:00Z', stepCount: 2, added: ['Sauvegarder la base'], removed: ['Vieux'], modified: ['Lire la config'] },
    ]);
    fixture.detectChanges();
    const versions = Array.from(el.querySelectorAll('.board__version')).map((v) => v.textContent ?? '');
    expect(versions[0]).toContain('v2');
    expect(versions[0]).toContain('+ Sauvegarder la base');
    expect(versions[0]).toContain('− Vieux');
    expect(versions[0]).toContain('~ Lire la config');
    expect(versions[1]).toContain('v1');

    // Le plan change pendant que l'historique est ouvert : relu.
    fixture.componentRef.setInput('journey', journey([chantier(4, { focus: true, plan: MIGRATION, planVersion: 4 })]));
    fixture.detectChanges();
    http.expectOne('/api/workspaces/w1/journey/chantiers/4/versions').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('.board__history')?.textContent).toContain('conservé à partir de la prochaine version');

    fixture.componentRef.setInput('journey', journey([chantier(4, { focus: true, plan: MIGRATION, planVersion: 5 })]));
    fixture.detectChanges();
    http.expectOne('/api/workspaces/w1/journey/chantiers/4/versions').flush('x', { status: 500, statusText: 'KO' });
    fixture.detectChanges();
    expect(el.querySelector('.board__history')?.textContent).toContain('n’a pas pu être lu');
  });

  it('se ferme par le bouton, par Échap et par un clic sur le voile', () => {
    const fixture = mount(journey([chantier(1, { focus: true })]));
    const el: HTMLElement = fixture.nativeElement;
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => closed++);
    (el.querySelector('.board__close') as HTMLButtonElement).click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    (el.querySelector('.board-scrim') as HTMLElement).click();
    expect(closed).toBe(3);
    expect(el.textContent).toContain('Pas encore de plan — phase Exécution');
  });

  it('sans chantier ouvert, il le dit', () => {
    const fixture = mount(journey([]));
    expect(fixture.nativeElement.textContent).toContain('Aucun chantier ouvert sur ce sujet.');
  });

  it('l’événement SSE « journey » signale un changement du parcours', () => {
    const journeys = TestBed.inject(JourneyService);
    const atelier = TestBed.inject(AtelierService) as unknown as {
      dispatchSseEvent(raw: string, handlers: unknown): void;
    };
    expect(journeys.liveChanges()).toBe(0);
    atelier.dispatchSseEvent('event: journey\ndata: {"tool":"update_plan_step","chantier":null}', {});
    expect(journeys.liveChanges()).toBe(1);
  });

  describe('SF-208-04 — les boutons', () => {
    const EXEC = plan([
      step('Lire', { status: 'VERIFIE', evidence: 'cat ok' }),
      step('Purger le cache'),
      step('Pousser en prod', { risk: 'EXTERNE', riskLabel: 'externe', waitsOn: 'feu-vert', waitsOnStatus: 'DEMANDE' }),
      step('Annoncer', { unlocked: false, blockedBy: ['étape 3'] }),
    ]);

    const actions = (el: HTMLElement, i: number) =>
      el.querySelectorAll<HTMLElement>('.board__step')[i];

    it('« Faire cette étape » envoie la demande visible ; bloquée ⇒ désactivée avec la raison', () => {
      const fixture = mount(journey([chantier(2, { focus: true, plan: EXEC })]));
      const el: HTMLElement = fixture.nativeElement;
      const sent: string[] = [];
      fixture.componentInstance.request.subscribe((r) => sent.push(r));
      expect(actions(el, 0).querySelector('.board__do')).toBeNull();
      (actions(el, 1).querySelector('.board__do') as HTMLButtonElement).click();
      expect(sent).toEqual(['Chantier 2 — fais l’étape 2 : Purger le cache']);
      const blocked = actions(el, 3).querySelector('.board__do') as HTMLButtonElement;
      expect(blocked.disabled).toBeTrue();
      expect(actions(el, 3).querySelector('.board__reason')?.textContent).toContain('bloquée : après l’étape 3');
    });

    it('plan non validé : « Faire cette étape » inactif, « plan à valider d’abord »', () => {
      const fixture = mount(journey([chantier(2, {
        focus: true, executing: false, plan: { ...EXEC, awaitingValidation: true },
      })]));
      const el: HTMLElement = fixture.nativeElement;
      expect((actions(el, 1).querySelector('.board__do') as HTMLButtonElement).disabled).toBeTrue();
      expect(actions(el, 1).querySelector('.board__reason')?.textContent).toContain('plan à valider d’abord');
    });

    it('étape EXTERNE : confirmation explicite ; Annuler n’envoie rien, Confirmer envoie', () => {
      const fixture = mount(journey([chantier(2, { focus: true, plan: EXEC })]));
      const el: HTMLElement = fixture.nativeElement;
      const sent: string[] = [];
      fixture.componentInstance.request.subscribe((r) => sent.push(r));
      (actions(el, 2).querySelector('.board__do') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(sent).toEqual([]);
      expect(actions(el, 2).querySelector('.board__confirm')?.textContent).toContain('hors du poste');
      (actions(el, 2).querySelector('.board__confirm-no') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(actions(el, 2).querySelector('.board__confirm')).toBeNull();
      expect(sent).toEqual([]);
      (actions(el, 2).querySelector('.board__do') as HTMLButtonElement).click();
      fixture.detectChanges();
      (actions(el, 2).querySelector('.board__confirm-yes') as HTMLButtonElement).click();
      expect(sent).toEqual(['Chantier 2 — fais l’étape 3 : Pousser en prod']);
    });

    it('« Relancer l’attente », « Voir la preuve », « Mettre au focus »', () => {
      const fixture = mount(journey([chantier(1, { focus: true }), chantier(2, { plan: EXEC })]));
      const el: HTMLElement = fixture.nativeElement;
      const sent: string[] = [];
      const focused: number[] = [];
      fixture.componentInstance.request.subscribe((r) => sent.push(r));
      fixture.componentInstance.focus.subscribe((n) => focused.push(n));
      expect(el.querySelector('.board__focus-btn')).toBeNull();
      (el.querySelectorAll('.board__tab')[1] as HTMLButtonElement).click();
      fixture.detectChanges();
      (el.querySelector('.board__focus-btn') as HTMLButtonElement).click();
      expect(focused).toEqual([2]);
      (actions(el, 2).querySelector('.board__remind') as HTMLButtonElement).click();
      expect(sent).toEqual(['Chantier 2 — relance l’attente « feu-vert » dont dépend l’étape 3 : Pousser en prod']);
      expect(actions(el, 0).querySelector('.board__detail')).toBeNull();
      (actions(el, 0).querySelector('.board__see-proof') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(actions(el, 0).querySelector('.board__proof--shown')?.textContent).toContain('cat ok');
    });

    it('sans droit d’envoi (lecture seule) : ni « Faire » ni « Relancer »', () => {
      const fixture = TestBed.createComponent(TerminalChantiersBoardComponent);
      fixture.componentRef.setInput('journey', journey([chantier(2, { focus: true, plan: EXEC })]));
      fixture.componentRef.setInput('canSend', false);
      fixture.detectChanges();
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.board__do')).toBeNull();
      expect(el.querySelector('.board__remind')).toBeNull();
      expect(el.querySelector('.board__see-proof')).not.toBeNull();
    });
  });
});
