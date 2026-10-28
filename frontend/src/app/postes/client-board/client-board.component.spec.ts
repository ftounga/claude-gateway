import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AutonomyRun } from '../../core/models/autonomy.models';
import { ClientBoard, ClientBoardChantier, ClientBoardLooseTask } from '../../core/models/client-board.models';
import { TerminalAction } from '../../core/models/terminal-actions.models';
import { SubjectJourney } from '../../core/models/journey.models';
import { AutonomyService } from '../../core/services/autonomy.service';
import { ClientBoardService } from '../../core/services/client-board.service';
import { JourneyService } from '../../core/services/journey.service';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';
import { CLIENT_BOARD_COLLAPSED_KEY, ClientBoardComponent } from './client-board.component';

/** F-220 / SF-220-03 — l'écran du tableau du client, service simulé. */
describe('ClientBoardComponent', () => {
  let fixture: ComponentFixture<ClientBoardComponent>;
  let component: ClientBoardComponent;
  let boards: jasmine.SpyObj<ClientBoardService>;
  let autonomy: jasmine.SpyObj<AutonomyService>;
  let journeys: jasmine.SpyObj<JourneyService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snack: jasmine.SpyObj<MatSnackBar>;
  let actions: jasmine.SpyObj<TerminalActionsService>;
  let dialogAnswer: boolean;

  const run: AutonomyRun = {
    id: 'r1', workspaceId: 'w3', chantierNumber: 1, chantierTitle: 'Bascule', status: 'RUNNING', turns: 1,
    stepsDone: 2, stepsTotal: 5, costUsd: 1.25, stopReason: null, errorCode: null, startedAt: '', finishedAt: null,
  };

  const card = (extra: Partial<ClientBoardChantier>): ClientBoardChantier => ({
    workspaceId: 'w1', workspaceName: 'ingress', hostTerminal: false, number: 1, title: 'Certificat',
    phase: 'EXECUTION', phaseLabel: 'Exécution', focus: true, openedAt: null, done: 1, total: 3,
    nextStep: { rank: 2, title: 'Remplacer le certificat' },
    unblocker: { who: 'OUTIL', label: 'L\'outil peut avancer', detail: 'Étape 2 : « Remplacer ».', step: 2,
      waitingOn: null, since: null },
    ready: true, missing: [], lastActivityAt: null, idleDays: 0, dormant: false, live: false, question: null,
    run: null, ...extra,
  });

  const outilReady = card({});
  const outilReady2 = card({ workspaceId: 'w2', workspaceName: 'api', number: 2, focus: false });
  const moiQuestion = card({
    workspaceId: 'w4', workspaceName: 'dns', number: 1,
    unblocker: { who: 'MOI', label: 'Ça attend votre décision', detail: 'Une question…', step: null,
      waitingOn: null, since: null },
    ready: false, missing: ['Une question ou une autorisation attend votre réponse dans ce sujet.'],
    question: 'Quel namespace viser ?', live: true,
  });
  const autruiDormant = card({
    workspaceId: 'w5', workspaceName: 'vault', number: 3, focus: false,
    unblocker: { who: 'AUTRUI', label: 'On attend quelqu\'un', detail: 'Étape 1 : en attente de « cert ».', step: 1,
      waitingOn: 'Gino', since: '2026-10-20T09:00:00Z' },
    ready: false, missing: ['a', 'b', 'c'], idleDays: 6, dormant: true,
  });
  const running = card({ workspaceId: 'w3', workspaceName: 'migration', number: 1, ready: false, run, live: true });

  const board = (chantiers: ClientBoardChantier[], runningCount = 0): ClientBoard => ({
    hostId: 'h1', hostName: 'EDENRED', generatedAt: '', dormantAfterDays: 2,
    counts: {
      outil: chantiers.filter((c) => c.unblocker?.who === 'OUTIL').length,
      moi: chantiers.filter((c) => c.unblocker?.who === 'MOI').length,
      autrui: chantiers.filter((c) => c.unblocker?.who === 'AUTRUI').length,
      live: 0, dormant: chantiers.filter((c) => c.dormant).length,
    },
    autonomy: { running: runningCount, maxParallel: 4 },
    chantiers,
  });

  function build(data: ClientBoard): void {
    boards = jasmine.createSpyObj<ClientBoardService>('ClientBoardService', ['board', 'snooze', 'unsnooze']);
    boards.board.and.returnValue(of(data));
    boards.snooze.and.returnValue(of({ number: 1, until: '2026-10-30T10:00:00' }));
    boards.unsnooze.and.returnValue(of(undefined));
    autonomy = jasmine.createSpyObj<AutonomyService>('AutonomyService', ['launch']);
    autonomy.launch.and.returnValue(of(run));
    journeys = jasmine.createSpyObj<JourneyService>('JourneyService', ['focus', 'closeChantier']);
    journeys.focus.and.returnValue(of({} as SubjectJourney));
    journeys.closeChantier.and.returnValue(of({} as SubjectJourney));
    dialogAnswer = true;
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.callFake(() => ({ afterClosed: () => of(dialogAnswer) }) as never);
    snack = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    actions = jasmine.createSpyObj<TerminalActionsService>('TerminalActionsService', ['close', 'changeStatus']);
    actions.close.and.returnValue(of({} as TerminalAction));
    actions.changeStatus.and.returnValue(of({} as TerminalAction));
    TestBed.configureTestingModule({
      imports: [ClientBoardComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: ClientBoardService, useValue: boards },
        { provide: AutonomyService, useValue: autonomy },
        { provide: JourneyService, useValue: journeys },
        { provide: TerminalActionsService, useValue: actions },
      ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    TestBed.overrideProvider(MatSnackBar, { useValue: snack });
    fixture = TestBed.createComponent(ClientBoardComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function blockIds(): (string | null)[] {
    return Array.from(el().querySelectorAll('[data-subject]')).map((b) => b.getAttribute('data-subject'));
  }

  function clearCollapsed(): void {
    try {
      localStorage.removeItem(CLIENT_BOARD_COLLAPSED_KEY);
    } catch {
      // stockage indisponible : rien à nettoyer
    }
  }

  beforeEach(() => clearCollapsed());
  afterEach(() => {
    fixture?.destroy();
    clearCollapsed();
  });

  it('SF-220-08 : un bloc par sujet — décision d\'abord, puis le plus dormant ; pastille icône + libellé', () => {
    build(board([outilReady, autruiDormant, moiQuestion]));
    expect(blockIds()).toEqual(['w4', 'w5', 'w1']);
    expect(el().querySelector('[data-group]')).toBeNull();
    const pill = el().querySelector('.cb__pill--MOI');
    expect(pill?.textContent).toContain('Ça attend votre décision');
    expect(pill?.querySelector('mat-icon')?.textContent).toContain('front_hand');
  });

  it('montre l\'ancienneté, à qui et depuis quand, la question, la prochaine étape et ce qui manque', () => {
    build(board([autruiDormant, moiQuestion, outilReady]));
    const dormant = el().querySelector('[data-key="w5#3"]') as HTMLElement;
    expect(dormant.querySelector('.cb__age--dormant')?.textContent).toContain('dormant depuis 6 j');
    expect(dormant.textContent).toContain('Gino');
    expect(dormant.textContent).toContain('+1 autre');
    const question = el().querySelector('[data-key="w4#1"]') as HTMLElement;
    expect(question.querySelector('.cb__question')?.textContent).toContain('Quel namespace viser ?');
    expect(question.querySelector('.cb__open')?.textContent).toContain('Répondre');
    expect(el().querySelector('[data-key="w1#1"]')?.textContent).toContain('2. Remplacer le certificat');
  });

  it('une course autonome : « En autonome — étape k/n », coût, ni case ni lancement', () => {
    build(board([running], 1));
    const c = el().querySelector('[data-key="w3#1"]') as HTMLElement;
    expect(c.textContent).toContain('En autonome — étape 3/5');
    expect(c.textContent).toContain('1.25 $');
    expect(c.querySelector('mat-checkbox')).toBeNull();
    expect(c.querySelector('.cb__launch')).toBeNull();
  });

  it('filtre Dormants, puis le retire', () => {
    build(board([autruiDormant, outilReady]));
    (el().querySelector('[data-filter="DORMANT"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el().querySelectorAll('.cb__card').length).toBe(1);
    (el().querySelector('[data-filter="DORMANT"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el().querySelectorAll('.cb__card').length).toBe(2);
  });

  it('Ouvrir : focus du chantier, puis son terminal', () => {
    build(board([outilReady2]));
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    component.open(outilReady2);
    expect(journeys.focus).toHaveBeenCalledWith('w2', 2);
    expect(navigate).toHaveBeenCalledWith(['/atelier', 'w2']);
  });

  it('Ouvrir : le terminal s\'ouvre même si le focus échoue', () => {
    build(board([outilReady2]));
    journeys.focus.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400 })));
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    component.open(outilReady2);
    expect(navigate).toHaveBeenCalledWith(['/atelier', 'w2']);
  });

  it('Lancer en autonome : la route F-219 ; un refus affiche le message du serveur', () => {
    build(board([outilReady]));
    component.launch(outilReady);
    expect(autonomy.launch).toHaveBeenCalledWith('w1', 1, 'STRICT');
    autonomy.launch.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { error: 'autonomy_subject_busy', message: 'Un tour tourne déjà dans ce sujet.' },
    })));
    component.launch(outilReady);
    expect(snack.open.calls.mostRecent().args[0]).toContain('Un tour tourne déjà dans ce sujet.');
  });

  it('multi-sélection : au-delà des places libres, refus écrit et rien ne part', () => {
    build(board([outilReady, outilReady2], 3));
    component.toggleSelected(outilReady);
    component.toggleSelected(outilReady2);
    fixture.detectChanges();
    expect(el().querySelector('.cb__refusal')?.textContent)
      .toContain('Quatre chantiers autonomes au maximum : 3 en cours, 1 place libre.');
    expect((el().querySelector('.cb__launch-all') as HTMLButtonElement).disabled).toBeTrue();
    component.launchSelected();
    expect(autonomy.launch).not.toHaveBeenCalled();
  });

  it('multi-sélection : chaque chantier part l\'un après l\'autre, bilan des refus', () => {
    build(board([outilReady, outilReady2]));
    autonomy.launch.and.callFake((ws: string) => ws === 'w2'
      ? throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'Le poste est hors ligne.' } }))
      : of(run));
    component.toggleSelected(outilReady);
    component.toggleSelected(outilReady2);
    component.launchSelected();
    expect(autonomy.launch.calls.allArgs()).toEqual([['w1', 1, 'STRICT'], ['w2', 2, 'STRICT']]);
    expect(snack.open.calls.mostRecent().args[0])
      .toBe('1 chantier lancé en autonome ; 1 refusé : api · chantier 2 — Le poste est hors ligne.');
    expect(component.selection().length).toBe(0);
  });

  it('Clore : confirmation, puis clôture ; annuler ne clôt rien', () => {
    build(board([outilReady]));
    dialogAnswer = false;
    component.close(outilReady);
    expect(journeys.closeChantier).not.toHaveBeenCalled();
    dialogAnswer = true;
    component.close(outilReady);
    expect(journeys.closeChantier).toHaveBeenCalledWith('w1', 1);
  });

  it('lecture en échec : message et Réessayer', () => {
    boards = jasmine.createSpyObj<ClientBoardService>('ClientBoardService', ['board']);
    build(board([]));
    boards.board.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    component.board.set(null);
    component.load('h1', true);
    fixture.detectChanges();
    expect(el().querySelector('.cb__error')?.textContent).toContain('Le tableau n\'a pas pu être lu.');
  });

  it('SF-220-06 : reporter appelle la route, annonce la date et relit', () => {
    build(board([outilReady]));
    boards.board.calls.reset();
    component.snooze(outilReady, 3);
    expect(boards.snooze).toHaveBeenCalledWith('w1', 1, 3);
    expect(snack.open.calls.mostRecent().args[0]).toBe('Chantier 1 reporté jusqu\'au 30/10.');
    expect(boards.board).toHaveBeenCalled();
  });

  it('SF-220-06 : un reporté sort des groupes, se range dans « Reportés » replié, puis revient', () => {
    const later = card({ workspaceId: 'w9', workspaceName: 'dns', number: 4, snoozedUntil: '2026-10-30T10:00:00' });
    build(board([outilReady, later]));
    expect(el().querySelector('[data-key="w9#4"]')).toBeNull();
    const toggle = el().querySelector('.cb__snoozed-toggle') as HTMLButtonElement;
    expect(toggle.textContent).toContain('Reportés (1)');
    toggle.click();
    fixture.detectChanges();
    const item = el().querySelector('.cb__snoozed-item[data-key="w9#4"]') as HTMLElement;
    expect(item.textContent).toContain('reporté jusqu\'au 30/10');
    (item.querySelector('.cb__unsnooze') as HTMLButtonElement).click();
    expect(boards.unsnooze).toHaveBeenCalledWith('w9', 4);
  });

  it('SF-220-06 : un refus du report s\'affiche', () => {
    build(board([outilReady]));
    boards.snooze.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { error: 'journey_invalid', message: 'Le chantier 1 n\'est pas ouvert.' },
    })));
    component.snooze(outilReady, 1);
    expect(snack.open.calls.mostRecent().args[0]).toContain('Le chantier 1 n\'est pas ouvert.');
  });

  it('aucun chantier : le dit', () => {
    build(board([]));
    expect(el().querySelector('.cb__empty')?.textContent).toContain('Aucun chantier ouvert chez ce client.');
  });

  // ---------------------------------------------------------------- SF-220-07 : hors chantier
  const toAsk: ClientBoardLooseTask = {
    actionId: 'a1', workspaceId: 'w1', workspaceName: 'ingress', hostTerminal: false,
    description: 'Ticket DNS chez l\'hébergeur', blocks: 'la bascule', status: 'A_FAIRE', qui: 'TIERS',
    categorie: null, person: 'Support', requestedTo: null, requestedAt: null, createdAt: null,
    unblocker: { who: 'MOI', label: 'Ça attend votre décision', detail: 'À demander à Support.', step: null,
      waitingOn: null, since: null },
    lastActivityAt: null, idleDays: 1, dormant: false, followUpDue: false,
  };
  const asked: ClientBoardLooseTask = {
    ...toAsk, actionId: 'a2', workspaceId: 'w2', workspaceName: 'vpn', description: 'Ouverture du VPN',
    status: 'DEMANDE', qui: 'CLIENT', idleDays: 5, dormant: true, followUpDue: true,
    unblocker: { who: 'AUTRUI', label: 'On attend quelqu\'un', detail: 'Demandé à Marc.', step: null,
      waitingOn: 'Marc', since: '2026-10-20T09:00:00Z' },
  };
  const withLoose = (chantiers: ClientBoardChantier[], tasks: ClientBoardLooseTask[]): ClientBoard =>
    ({ ...board(chantiers), horsChantier: tasks });

  it('SF-220-07/08 : les attentes hors chantier dans le bloc de leur sujet, sous ses chantiers', () => {
    build(withLoose([outilReady], [asked, toAsk]));
    // w1 (ingress) a une décision hors chantier : il passe avant w2 (vpn), pourtant plus dormant
    expect(blockIds()).toEqual(['w1', 'w2']);
    expect(el().querySelectorAll('[data-section="hors-chantier"]').length).toBe(2);
    const w1 = el().querySelector('[data-subject="w1"]') as HTMLElement;
    const children = Array.from(w1.querySelector('.cb__block-body')!.children).map((n) => n.className);
    expect(children[0]).toContain('cb__cards');
    expect(children[1]).toContain('cb__loose');
    const section = w1.querySelector('[data-section="hors-chantier"]') as HTMLElement;
    expect(section.textContent).toContain('Hors chantier');
    const mine = section.querySelector('[data-action="a1"]') as HTMLElement;
    expect(mine.querySelector('.cb__pill--MOI')?.textContent).toContain('Ça attend votre décision');
    expect(mine.querySelector('.cb__pill--MOI mat-icon')?.textContent).toContain('front_hand');
    expect(mine.textContent).toContain('À demander à Support.');
    expect(w1.querySelector('.cb__block-name')?.textContent).toContain('ingress');
    const theirs = el().querySelector('[data-subject="w2"] [data-action="a2"]') as HTMLElement;
    expect(theirs.textContent).toContain('Marc');
    expect(theirs.textContent).toContain('dormant depuis 5 j');
    expect(theirs.textContent).toContain('à relancer');
    expect(theirs.querySelector('.cb__task-requested')).toBeNull();
  });

  it('SF-220-07 : marquer fait, marquer demandé, ouvrir le sujet — routes existantes, puis relecture', () => {
    build(withLoose([], [toAsk]));
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    boards.board.calls.reset();
    (el().querySelector('[data-action="a1"] .cb__task-requested') as HTMLButtonElement).click();
    expect(actions.changeStatus).toHaveBeenCalledWith('w1', 'a1', { status: 'DEMANDE' });
    (el().querySelector('[data-action="a1"] .cb__task-done') as HTMLButtonElement).click();
    expect(actions.close).toHaveBeenCalledWith('w1', 'a1');
    expect(boards.board).toHaveBeenCalledTimes(2);
    (el().querySelector('[data-action="a1"] .cb__task-open') as HTMLButtonElement).click();
    expect(router.navigate).toHaveBeenCalledWith(['/atelier', 'w1']);
    // sans chantier, la section reste montrée et l'état vide n'est pas affiché
    expect(el().querySelector('.cb__empty')).toBeNull();
  });

  it('SF-220-07 : le filtre « quelqu\'un » ne garde que les attentes demandées ; un refus s\'affiche', () => {
    build(withLoose([], [asked, toAsk]));
    component.toggleFilter('AUTRUI');
    fixture.detectChanges();
    expect(el().querySelector('[data-action="a1"]')).toBeNull();
    expect(el().querySelector('[data-action="a2"]')).not.toBeNull();
    actions.close.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 404, error: { message: 'Action introuvable.' },
    })));
    component.markDone(asked);
    expect(snack.open.calls.mostRecent().args[0]).toContain('Action introuvable.');
  });

  // ------------------------------------------------------------------ SF-220-08 : rangé par sujet

  it('SF-220-08 : dans un bloc, l\'outil passe avant votre décision, puis quelqu\'un', () => {
    const sameMoi = card({ ...moiQuestion, workspaceId: 'w1', workspaceName: 'ingress', number: 2 });
    const sameAutrui = card({ ...autruiDormant, workspaceId: 'w1', workspaceName: 'ingress', number: 3 });
    build(board([sameAutrui, sameMoi, outilReady]));
    expect(blockIds()).toEqual(['w1']);
    const keys = Array.from(el().querySelectorAll('[data-subject="w1"] [data-key]')).map((c) => c.getAttribute('data-key'));
    expect(keys).toEqual(['w1#1', 'w1#2', 'w1#3']);
  });

  it('SF-220-08 : l\'en-tête dit le nom, les compteurs (icône + libellé), le plus dormant et ouvre le terminal', () => {
    const sameOutil = card({ workspaceId: 'w5', workspaceName: 'vault', number: 4 });
    build(board([autruiDormant, sameOutil]));
    const head = el().querySelector('[data-subject="w5"] .cb__block-head') as HTMLElement;
    expect(head.querySelector('.cb__block-name')?.textContent).toContain('vault');
    const outil = head.querySelector('[data-count="OUTIL"]') as HTMLElement;
    expect(outil.textContent).toContain('1 l\'outil avance');
    expect(outil.querySelector('mat-icon')?.textContent).toContain('bolt');
    expect(head.querySelector('[data-count="AUTRUI"]')?.textContent).toContain('1 on attend');
    expect(head.querySelector('[data-count="MOI"]')).toBeNull();
    expect(head.querySelector('.cb__age--dormant')?.textContent).toContain('dormant depuis 6 j');
    const link = head.querySelector('.cb__block-open') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/atelier/w5');
    expect(link.getAttribute('aria-label')).toBe('Ouvrir le terminal du sujet vault');
  });

  it('SF-220-08 : le terminal du poste est un sujet comme un autre', () => {
    build(board([card({ workspaceId: 'wt', workspaceName: 'poste', hostTerminal: true })]));
    expect(el().querySelector('[data-subject="wt"] .cb__block-name')?.textContent).toContain('Terminal du poste');
  });

  it('SF-220-08 : replier un bloc (aria-expanded), le mémoriser, le relire au rendu suivant', () => {
    build(board([outilReady, outilReady2]));
    const toggle = el().querySelector('[data-subject="w1"] .cb__block-toggle') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(toggle.getAttribute('aria-controls')).toBe('cb-block-w1');
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(el().querySelector('[data-key="w1#1"]')).toBeNull();
    expect(el().querySelector('[data-key="w2#2"]')).not.toBeNull();
    expect(JSON.parse(localStorage.getItem(CLIENT_BOARD_COLLAPSED_KEY)!)).toEqual(['w1']);
    fixture.destroy();
    TestBed.resetTestingModule();
    build(board([outilReady, outilReady2]));
    expect(el().querySelector('[data-subject="w1"] .cb__block-toggle')?.getAttribute('aria-expanded')).toBe('false');
    (el().querySelector('[data-subject="w1"] .cb__block-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el().querySelector('[data-key="w1#1"]')).not.toBeNull();
    expect(JSON.parse(localStorage.getItem(CLIENT_BOARD_COLLAPSED_KEY)!)).toEqual([]);
  });

  it('SF-220-08 : un localStorage qui lève ou illisible ne casse rien — tout déplié, repli en mémoire', () => {
    localStorage.setItem(CLIENT_BOARD_COLLAPSED_KEY, '{cassé');
    build(board([outilReady]));
    expect(el().querySelector('[data-key="w1#1"]')).not.toBeNull();
    fixture.destroy();
    TestBed.resetTestingModule();
    spyOn(Storage.prototype, 'getItem').and.throwError('refusé');
    spyOn(Storage.prototype, 'setItem').and.throwError('plein');
    build(board([outilReady]));
    expect(el().querySelector('[data-key="w1#1"]')).not.toBeNull();
    (el().querySelector('[data-subject="w1"] .cb__block-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el().querySelector('[data-key="w1#1"]')).toBeNull();
  });

  it('SF-220-08 : le filtre agit dans les blocs et masque les blocs vides ; les compteurs du bloc restent', () => {
    const sameMoi = card({ ...moiQuestion, workspaceId: 'w1', workspaceName: 'ingress', number: 2 });
    build(board([outilReady, sameMoi, autruiDormant]));
    (el().querySelector('[data-filter="MOI"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(blockIds()).toEqual(['w1']);
    expect(Array.from(el().querySelectorAll('[data-key]')).map((c) => c.getAttribute('data-key'))).toEqual(['w1#2']);
    expect(el().querySelector('[data-subject="w1"] [data-count="OUTIL"]')).not.toBeNull();
    component.filter.set('DORMANT');
    fixture.detectChanges();
    expect(blockIds()).toEqual(['w5']);
  });

  it('SF-220-08 : un sujet dont tout est reporté n\'a pas de bloc ; un filtre vide le dit', () => {
    const later = card({ workspaceId: 'w9', workspaceName: 'dns', number: 4, snoozedUntil: '2026-10-30T10:00:00' });
    build(board([later]));
    expect(blockIds()).toEqual([]);
    expect(el().querySelector('.cb__none')?.textContent).toContain('Tous les chantiers ouverts sont reportés.');
    expect(el().querySelector('.cb__snoozed-toggle')?.textContent).toContain('Reportés (1)');
    fixture.destroy();
    TestBed.resetTestingModule();
    build(board([outilReady]));
    component.toggleFilter('MOI');
    fixture.detectChanges();
    expect(el().querySelector('.cb__none')?.textContent).toContain('Rien pour ce filtre.');
  });

  it('SF-220-08 : la multi-sélection traverse les blocs, même repliés', () => {
    build(board([outilReady, outilReady2]));
    component.toggleSelected(outilReady);
    (el().querySelector('[data-subject="w1"] .cb__block-toggle') as HTMLButtonElement).click();
    component.toggleSelected(outilReady2);
    fixture.detectChanges();
    expect(el().querySelector('.cb__selection-count')?.textContent).toContain('2 sélectionnés');
    component.launchSelected();
    expect(autonomy.launch.calls.allArgs()).toEqual([['w1', 1, 'STRICT'], ['w2', 2, 'STRICT']]);
  });

  // ------------------------------------------------------------------ SF-219-05 : avancer jusqu'au blocage

  const advanceOnly = card({
    workspaceId: 'w6', workspaceName: 'edge', number: 7, focus: false, ready: false, canAdvance: true,
    missing: ['Étape 3 : en attente de « cert » (demandé).'],
  });

  it('SF-219-05 : une carte qui peut avancer montre [Avancer jusqu\'au blocage], qui part dans ce mode', () => {
    build(board([advanceOnly]));
    const cardEl = el().querySelector('[data-key]') as HTMLElement;
    expect(cardEl.querySelector('.cb__launch')).toBeNull();
    const advance = cardEl.querySelector('.cb__advance') as HTMLButtonElement;
    expect(advance.textContent).toContain('Avancer jusqu’au blocage');
    advance.click();
    expect(autonomy.launch).toHaveBeenCalledWith('w6', 7, 'UNTIL_BLOCKED');
    expect(snack.open.calls.mostRecent().args[0]).toBe('Chantier 7 avance jusqu\'au blocage.');
  });

  it('SF-219-05 : ni blocage possible ni prêt → aucun geste de lancement, pas sélectionnable', () => {
    build(board([autruiDormant]));
    expect(el().querySelector('.cb__advance')).toBeNull();
    expect(component.selectable(autruiDormant)).toBeFalse();
  });

  it('SF-219-05 : multi-sélection mêlant prêt et « peut avancer » → seul [Avancer jusqu\'au blocage (k)]', () => {
    build(board([outilReady, advanceOnly]));
    component.toggleSelected(outilReady);
    component.toggleSelected(advanceOnly);
    fixture.detectChanges();
    expect(el().querySelector('.cb__launch-all')).toBeNull();
    const all = el().querySelector('.cb__advance-all') as HTMLButtonElement;
    expect(all.textContent).toContain('Avancer jusqu’au blocage (2)');
    component.launchSelected('STRICT');
    expect(autonomy.launch).not.toHaveBeenCalled();
    all.click();
    expect(autonomy.launch.calls.allArgs()).toEqual([['w1', 1, 'UNTIL_BLOCKED'], ['w6', 7, 'UNTIL_BLOCKED']]);
  });

  it('SF-219-05 : une course jusqu\'au blocage se lit « Jusqu\'au blocage — étape k/n »', () => {
    build(board([card({ workspaceId: 'w3', number: 1, ready: false, live: true, run: { ...run, mode: 'UNTIL_BLOCKED' } })]));
    expect(component.runLabel(component.board()!.chantiers[0])).toBe('Jusqu’au blocage — étape 3/5');
  });
});
