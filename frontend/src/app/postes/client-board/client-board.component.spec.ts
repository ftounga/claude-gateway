import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AutonomyRun } from '../../core/models/autonomy.models';
import { ClientBoard, ClientBoardChantier } from '../../core/models/client-board.models';
import { SubjectJourney } from '../../core/models/journey.models';
import { AutonomyService } from '../../core/services/autonomy.service';
import { ClientBoardService } from '../../core/services/client-board.service';
import { JourneyService } from '../../core/services/journey.service';
import { ClientBoardComponent } from './client-board.component';

/** F-220 / SF-220-03 — l'écran du tableau du client, service simulé. */
describe('ClientBoardComponent', () => {
  let fixture: ComponentFixture<ClientBoardComponent>;
  let component: ClientBoardComponent;
  let boards: jasmine.SpyObj<ClientBoardService>;
  let autonomy: jasmine.SpyObj<AutonomyService>;
  let journeys: jasmine.SpyObj<JourneyService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snack: jasmine.SpyObj<MatSnackBar>;
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
    TestBed.configureTestingModule({
      imports: [ClientBoardComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: ClientBoardService, useValue: boards },
        { provide: AutonomyService, useValue: autonomy },
        { provide: JourneyService, useValue: journeys },
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

  afterEach(() => fixture?.destroy());

  it('groupe Outil → Ma décision → Quelqu\'un, chaque pastille écrit icône et libellé', () => {
    build(board([autruiDormant, moiQuestion, outilReady]));
    const groups = Array.from(el().querySelectorAll('[data-group]')).map((g) => g.getAttribute('data-group'));
    expect(groups).toEqual(['OUTIL', 'MOI', 'AUTRUI']);
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
    expect(autonomy.launch).toHaveBeenCalledWith('w1', 1);
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
    expect(autonomy.launch.calls.allArgs()).toEqual([['w1', 1], ['w2', 2]]);
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
});
