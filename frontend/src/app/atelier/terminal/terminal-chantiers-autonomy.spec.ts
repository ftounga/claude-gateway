import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';

import { TerminalChantiersBoardComponent } from './terminal-chantiers-board.component';
import { JourneyPlanStep, OpenChantier, SubjectJourney, stepState, stepStatusLabel } from '../../core/models/journey.models';
import { AutonomyBoard, AutonomyBoardEntry, AutonomyRun, autonomyCurrentStep } from '../../core/models/autonomy.models';
import { expandSlashCommand } from './slash-commands';

const step = (title: string, extra: Partial<JourneyPlanStep> = {}): JourneyPlanStep => ({
  title, risk: 'REVERSIBLE', riskLabel: 'modification réversible', verify: null, rollback: null, waitsOn: null,
  waitsOnStatus: null, status: 'A_FAIRE', evidence: null, changed: false, dependsOn: [], unlocked: true,
  blockedBy: [], inProgress: false, subSteps: [], ...extra,
});

const chantier = (number: number, extra: Partial<OpenChantier> = {}): OpenChantier => ({
  number, title: `Titre ${number}`, openedAt: null, phase: 'EXECUTION', phaseLabel: 'Exécution', focus: number === 1,
  planVersion: 3, validatedVersion: 3, awaitingValidation: false, diagnosisPending: false, closeProposed: false,
  executing: true, done: 2, total: 7, plan: null, ...extra,
});

const journey = (chantiers: OpenChantier[]): SubjectJourney => ({
  mode: 'GUIDE', phase: 'EXECUTION', phaseLabel: 'Exécution', phaseChangedAt: null, chantiers,
  chantier: { number: 1, title: null, openedAt: null },
}) as SubjectJourney;

const run = (extra: Partial<AutonomyRun> = {}): AutonomyRun => ({
  id: 'r1', workspaceId: 'w1', chantierNumber: 1, chantierTitle: 'Titre 1', status: 'RUNNING', turns: 1,
  stepsDone: 2, stepsTotal: 7, costUsd: 0.4, stopReason: null, errorCode: null, startedAt: '2026-10-27T10:00:00Z',
  finishedAt: null, ...extra,
});

const entry = (extra: Partial<AutonomyBoardEntry> = {}): AutonomyBoardEntry => ({
  number: 1, title: 'Titre 1', preflight: { number: 1, ready: true, reasons: [] }, run: null,
  estimate: { usd: 1.5, turns: 3, basis: 'coût moyen de vos tours sur 30 jours × 3 tour(s)' }, ...extra,
});

const board = (entries: AutonomyBoardEntry[], running = 0): AutonomyBoard => ({ chantiers: entries, running, maxParallel: 4 });

/** **L'écran du chantier autonome** (F-219 / SF-219-03). */
describe('F-219 / SF-219-03 — l’écran du chantier autonome', () => {
  let http: HttpTestingController;
  let snack: jasmine.SpyObj<MatSnackBar>;

  beforeEach(() => {
    snack = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: MatSnackBar, useValue: snack }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const mount = (j: SubjectJourney, b: AutonomyBoard, canSend = true) => {
    const fixture = TestBed.createComponent(TerminalChantiersBoardComponent);
    fixture.componentRef.setInput('journey', j);
    fixture.componentRef.setInput('workspaceId', 'w1');
    fixture.componentRef.setInput('canSend', canSend);
    fixture.detectChanges();
    http.expectOne('/api/workspaces/w1/journey/autonomy').flush(b);
    fixture.detectChanges();
    return fixture;
  };

  const el = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement as HTMLElement;

  it('prêt : badge « Prêt à partir », estimation, et [Lancer] part au premier clic', () => {
    const fixture = mount(journey([chantier(1)]), board([entry()], 1));
    const html = el(fixture);
    expect(html.querySelector('.auto__status--ready')?.textContent).toContain('Prêt à partir');
    expect(html.querySelector('.auto__meta')?.textContent).toContain('≈ 1.50 $');
    expect(html.querySelector('.auto__meta')?.textContent).toContain('1/4 chantiers autonomes en cours');

    (html.querySelector('.auto__go') as HTMLButtonElement).click();
    const req = http.expectOne('/api/workspaces/w1/journey/chantiers/1/autonomy');
    expect(req.request.method).toBe('POST');
    req.flush(run());
    expect(snack.open).toHaveBeenCalledWith('Chantier 1 lancé. Vous pouvez quitter l’écran.', 'OK', jasmine.any(Object));
    http.expectOne('/api/workspaces/w1/journey/autonomy').flush(board([entry({ run: run() })], 2));
    fixture.detectChanges();
    expect(html.querySelector('.auto__status--running')?.textContent).toContain('En autonome — étape 3/7');
    expect(html.querySelector('.board__tab-auto')?.textContent).toContain('autonome');
    fixture.destroy(); // arrête la relecture
  });

  it('pas prêt : les raisons du serveur, aucun bouton de lancement', () => {
    const fixture = mount(journey([chantier(1)]), board([entry({
      preflight: { number: 1, ready: false, reasons: [
        { code: 'STEP_BLOCKED', label: 'Étape 2 bloquée : accès VPN refusé', step: 2 },
        { code: 'WAIT_OPEN', label: 'Étape 4 : en attente de « cert » (demandé).', step: 4 },
      ] },
    })]));
    const html = el(fixture);
    expect(html.querySelector('.auto__status--not-ready')?.textContent).toContain('Pas prêt à être lancé');
    const reasons = Array.from(html.querySelectorAll('.auto__reasons li')).map((li) => li.textContent?.trim());
    expect(reasons).toEqual(['Étape 2 bloquée : accès VPN refusé', 'Étape 4 : en attente de « cert » (demandé).']);
    expect(html.querySelector('.auto__go')).toBeNull();
  });

  it('en cours : « En autonome — étape k/n » et [Reprendre la main]', () => {
    const fixture = mount(journey([chantier(1)]), board([entry({ run: run() })], 1));
    const html = el(fixture);
    expect(html.querySelector('.auto__status--running')?.textContent).toContain('étape 3/7');
    (html.querySelector('.auto__stop') as HTMLButtonElement).click();
    const req = http.expectOne('/api/workspaces/w1/journey/autonomy/stop?chantier=1');
    expect(req.request.method).toBe('POST');
    req.flush(run({ status: 'STOPPED', stopReason: 'Reprise en main.' }));
    expect(snack.open).toHaveBeenCalledWith('Vous avez repris la main sur le chantier 1.', 'OK', jasmine.any(Object));
    http.expectOne('/api/workspaces/w1/journey/autonomy').flush(board([entry({ run: run({ status: 'STOPPED', stopReason: 'Reprise en main.' }) })]));
    fixture.detectChanges();
    expect(html.querySelector('.auto__last')?.textContent).toContain('Dernière course : arrêtée — Reprise en main.');
    fixture.destroy();
  });

  it('409 au lancement : le message du serveur et sa première raison', () => {
    const fixture = mount(journey([chantier(1)]), board([entry()]));
    (el(fixture).querySelector('.auto__go') as HTMLButtonElement).click();
    http.expectOne('/api/workspaces/w1/journey/chantiers/1/autonomy').flush(
      { error: 'autonomy_limit_reached', message: 'Quatre chantiers autonomes au maximum en même temps.' },
      { status: 409, statusText: 'Conflict' });
    expect(snack.open).toHaveBeenCalledWith('Quatre chantiers autonomes au maximum en même temps.', 'OK', jasmine.any(Object));
    http.expectOne('/api/workspaces/w1/journey/autonomy').flush(board([entry()]));
  });

  it('lecture seule : l’état reste lisible, sans bouton', () => {
    const fixture = mount(journey([chantier(1)]), board([entry()]), false);
    expect(el(fixture).querySelector('.auto__status--ready')).not.toBeNull();
    expect(el(fixture).querySelector('.auto__go')).toBeNull();
  });

  it('étape BLOQUE : état « bloquée » avec la raison de l’agent', () => {
    const blocked = step('Basculer le DNS', { status: 'BLOQUE', evidence: 'accès registrar manquant', unlocked: true });
    expect(stepState(blocked)).toBe('blocked');
    expect(stepStatusLabel('BLOQUE')).toBe('bloquée');
    const fixture = mount(journey([chantier(1, { plan: {
      version: 3, validatedVersion: 3, validatedAt: null, awaitingValidation: false, amendment: false, waitingInputs: 0,
      steps: [blocked],
    } })]), board([entry()]));
    expect(el(fixture).querySelector('.board__blocked')?.textContent).toContain('bloquée : accès registrar manquant');
  });

  it('fonctions pures et /autonome', () => {
    expect(autonomyCurrentStep(2, 7)).toBe(3);
    expect(autonomyCurrentStep(7, 7)).toBe(7);
    expect(autonomyCurrentStep(0, 0)).toBe(0);
    const prompt = expandSlashCommand('/autonome 2') ?? '';
    expect(prompt).toContain('lancer_chantier_autonome');
    expect(prompt.endsWith('2')).toBeTrue();
  });

  // ------------------------------------------------------------------ SF-219-05 : avancer jusqu'au blocage

  const gino = { who: 'AUTRUI', label: 'On attend quelqu\'un', detail: 'Étape 4 : en attente de « cert » auprès de Gino (demandé).',
    step: 4, waitingOn: 'Gino', since: null };
  const decision = { who: 'MOI', label: 'Ça attend votre décision', detail: 'Étape 2 bloquée : quel domaine ?',
    step: 2, waitingOn: null, since: null };

  it('SF-219-05 : préflight rouge mais du vert → [Lancer jusqu’au blocage] et « Ce qui restera » groupé', () => {
    const fixture = mount(journey([chantier(1)]), board([entry({
      preflight: { number: 1, ready: false, reasons: [{ code: 'WAIT_OPEN', label: 'Étape 4 : en attente.', step: 4 }],
        advance: { possible: true, toolSteps: [1, 3], remaining: [decision, gino] as never,
          label: '2 étape(s) à la portée de l’outil ; 1 décision(s) vous attendent ; on attend Gino.' } },
    })], 1));
    const html = el(fixture);
    expect(html.querySelector('.auto__status--advance')?.textContent).toContain('Peut être lancé jusqu’au blocage');
    expect(html.querySelector('.auto__status--not-ready')).toBeNull();
    const rest = html.querySelector('.auto__rest') as HTMLElement;
    expect(rest.getAttribute('aria-label')).toBe('Ce qui restera');
    expect(rest.querySelector('[data-who="MOI"]')?.textContent).toContain('Ça attend votre décision');
    expect(rest.querySelector('[data-who="AUTRUI"]')?.textContent).toContain('On attend quelqu’un');
    expect(rest.textContent).toContain('quel domaine ?');
    expect(rest.textContent).toContain('Gino');

    (html.querySelector('.auto__advance') as HTMLButtonElement).click();
    const req = http.expectOne('/api/workspaces/w1/journey/chantiers/1/autonomy?mode=UNTIL_BLOCKED');
    expect(req.request.method).toBe('POST');
    req.flush(run({ mode: 'UNTIL_BLOCKED' }));
    expect(snack.open).toHaveBeenCalledWith('Chantier 1 lancé jusqu’au blocage. Vous pouvez quitter l’écran.', 'OK', jasmine.any(Object));
    http.expectOne('/api/workspaces/w1/journey/autonomy').flush(board([entry({ run: run({ mode: 'UNTIL_BLOCKED' }) })], 2));
    fixture.detectChanges();
    expect(html.querySelector('.auto__status--running')?.textContent).toContain('Jusqu’au blocage — étape 3/7');
    fixture.destroy();
  });

  it('SF-219-05 : prêt → les deux boutons côte à côte', () => {
    const fixture = mount(journey([chantier(1)]), board([entry({
      preflight: { number: 1, ready: true, reasons: [], advance: { possible: true, toolSteps: [3], remaining: [], label: null } },
    })]));
    const html = el(fixture);
    expect(html.querySelector('.auto__go')?.textContent?.trim()).toMatch(/Lancer$/);
    expect(html.querySelector('.auto__advance')?.textContent).toContain('Lancer jusqu’au blocage');
  });

  it('SF-219-05 : course PARTIAL et plus rien à portée → « avancée jusqu’au blocage » et « Ce qui reste »', () => {
    const fixture = mount(journey([chantier(1)]), board([entry({
      preflight: { number: 1, ready: false, reasons: [{ code: 'WAIT_OPEN', label: 'Étape 4 : en attente.', step: 4 }],
        advance: { possible: false, toolSteps: [], remaining: [gino] as never, label: 'Aucune étape n’est à la portée de l’outil.' } },
      run: run({ status: 'PARTIAL', mode: 'UNTIL_BLOCKED', stopReason: 'Avancé jusqu’au blocage : on attend Gino.' }),
    })]));
    const html = el(fixture);
    expect(html.querySelector('.auto__advance')).toBeNull();
    expect(html.querySelector('.auto__rest')?.getAttribute('aria-label')).toBe('Ce qui reste');
    expect(html.querySelector('.auto__last')?.textContent).toContain('avancée jusqu’au blocage');
  });

  // ------------------------------------------------------------ F-226 / SF-226-03 : un seul vocabulaire

  const OLD_VERBS = ['Lancer en autonome', 'Avancer jusqu’au blocage', 'Mettre au focus', 'Afficher à côté'];

  it('SF-226-03 : aucun ancien verbe, quel que soit l’état (prêt, peut avancer, pas prêt, en cours)', () => {
    const states: AutonomyBoardEntry[] = [
      entry({ preflight: { number: 1, ready: true, reasons: [], advance: { possible: true, toolSteps: [3], remaining: [], label: null } } }),
      entry({ preflight: { number: 1, ready: false, reasons: [], advance: { possible: true, toolSteps: [3], remaining: [], label: '1 étape' } } }),
      entry({ preflight: { number: 1, ready: false, reasons: [{ code: 'X', label: 'Plan non validé.', step: null }] } }),
      entry({ run: run() }),
    ];
    for (const state of states) {
      const fixture = mount(journey([chantier(1), chantier(2, { focus: false })]), board([state]));
      const text = el(fixture).textContent ?? '';
      for (const verb of OLD_VERBS) {
        expect(text).not.toContain(verb);
      }
      fixture.destroy();
    }
  });

  it('SF-226-03 : un autre chantier que celui du volet → « Lancer à côté » / « Lancer à côté jusqu’au blocage »', () => {
    const fixture = TestBed.createComponent(TerminalChantiersBoardComponent);
    fixture.componentRef.setInput('journey', journey([chantier(1, { focus: false }), chantier(2, { focus: true })]));
    fixture.componentRef.setInput('workspaceId', 'w1');
    fixture.componentRef.setInput('canSend', true);
    fixture.componentRef.setInput('shownChantier', 2);
    fixture.detectChanges();
    http.expectOne('/api/workspaces/w1/journey/autonomy').flush(board([entry({
      preflight: { number: 1, ready: true, reasons: [], advance: { possible: true, toolSteps: [3], remaining: [], label: null } },
    })]));
    fixture.componentInstance.select(1);
    fixture.detectChanges();
    const html = el(fixture);
    expect(html.querySelector('.auto__go')?.textContent).toContain('Lancer à côté');
    expect(html.querySelector('.auto__advance')?.textContent).toContain('Lancer à côté jusqu’au blocage');
    fixture.destroy();
  });

  it('SF-226-03 : lancer et reprendre la main émettent lanesChanged (l’écran relit les fils actifs)', () => {
    const fixture = mount(journey([chantier(1)]), board([entry()]));
    let emitted = 0;
    fixture.componentInstance.lanesChanged.subscribe(() => emitted++);
    (el(fixture).querySelector('.auto__go') as HTMLButtonElement).click();
    http.expectOne('/api/workspaces/w1/journey/chantiers/1/autonomy').flush(run());
    http.expectOne('/api/workspaces/w1/journey/autonomy').flush(board([entry({ run: run() })], 1));
    expect(emitted).toBe(1);
    fixture.detectChanges();
    (el(fixture).querySelector('.auto__stop') as HTMLButtonElement).click();
    http.expectOne('/api/workspaces/w1/journey/autonomy/stop?chantier=1').flush(run({ status: 'STOPPED' }));
    http.expectOne('/api/workspaces/w1/journey/autonomy').flush(board([entry()]));
    expect(emitted).toBe(2);
    fixture.destroy();
  });
});
