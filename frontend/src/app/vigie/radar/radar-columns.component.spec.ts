import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarRef, TextOnlySnackBar } from '@angular/material/snack-bar';
import { Subject, of, throwError } from 'rxjs';

import {
  BoardCommitment,
  BoardSubject,
  RadarBoard,
  RadarCommitmentView,
  RadarCorrection,
} from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { CloseSubjectDialogComponent } from './close-subject-dialog.component';
import { RadarColumnsComponent } from './radar-columns.component';
import { postponeDate } from './radar-columns';

/** Les trois colonnes et leurs gestes (F-102 / SF-102-02). */
describe('RadarColumnsComponent', () => {
  let fixture: ComponentFixture<RadarColumnsComponent>;
  let component: RadarColumnsComponent;
  let radar: jasmine.SpyObj<RadarService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snackAction: Subject<void>;
  let dialogResult: unknown;
  let changed: number;

  const correction = (id = 'corr1'): RadarCorrection =>
    ({ id, subjectId: 's1', action: 'DONE', createdAt: '2026-09-15T08:00:00Z', undoneAt: null });

  const commitment = (extra: Partial<RadarCommitmentView> = {}): RadarCommitmentView => ({
    id: 'c1', subjectId: 's1', subjectName: 'MFA prestataires', direction: 'ME_TO_OTHER',
    description: 'Présenter Sophie à Karim', fromPerson: null, toPerson: null, otherPerson: null,
    dueDate: '2026-09-12', dueDeduced: false, status: 'OPEN', certainty: 'CERTAIN', sovereign: false,
    disowned: false, evidenceIds: ['e1'], followUpDueOn: null, followUpDue: false, ...extra,
  });

  const item = (c: Partial<RadarCommitmentView> = {}, extra: Partial<BoardCommitment> = {}): BoardCommitment => ({
    commitment: commitment(c), source: 'TEAMS_MESSAGE', sourceAt: '2026-09-11T09:00:00Z',
    deepLink: 'https://teams.microsoft.com/l/message/1', question: false, due: true, overdueDays: 3, ...extra,
  });

  const subject = (extra: Partial<BoardSubject['subject']> = {}, line: string | null = 'Périmètre validé.'): BoardSubject => ({
    subject: { id: 's1', name: 'MFA prestataires', state: 'ADVANCING', nextStep: null, dueDate: null,
      lastActivityAt: new Date().toISOString(), openCommitments: 1, awake: false, ...extra },
    line, sources: 6, people: ['Paul Martin', 'Sophie Laurent'],
  });

  const board = (): RadarBoard => ({
    toDo: [
      item(),
      item({ id: 'c2', description: 'Rédiger la note DSI', certainty: 'PROBABLE', dueDate: null, evidenceIds: [] },
        { question: true, due: false, overdueDays: 0, deepLink: null, source: 'TEAMS_MEETING' }),
    ],
    subjects: [
      subject(),
      subject({ id: 's2', name: 'Migration LDAP', state: 'CLOSE_PROPOSED' }, 'Dernier engagement tenu.'),
      subject({ id: 's3', name: 'Annuaire', state: 'CLOSED', awake: true }, null),
    ],
    waiting: [
      item({ id: 'c3', direction: 'OTHER_TO_ME', description: 'Retour de l\'éditeur SSO', dueDate: null,
        fromPerson: { id: 'p1', displayName: 'Julie Robert' }, followUpDue: true },
      { overdueDays: 0, source: 'PASTED_MAIL' }),
    ],
  });

  function build(first: RadarBoard | Error = board()): HTMLElement {
    radar = jasmine.createSpyObj<RadarService>('RadarService', ['board', 'correctCommitment', 'closeSubject',
      'confirmClosure', 'rejectClosure', 'dismissWake', 'setSubjectState', 'undo']);
    radar.board.and.returnValue(first instanceof Error ? throwError(() => first) : of(first));
    radar.correctCommitment.and.returnValue(of(correction()));
    radar.undo.and.returnValue(of(correction()));
    snackAction = new Subject<void>();
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    snackBar.open.and.returnValue({ onAction: () => snackAction.asObservable() } as MatSnackBarRef<TextOnlySnackBar>);
    dialogResult = undefined;
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.callFake((() => ({ afterClosed: () => of(dialogResult) })) as never);
    TestBed.configureTestingModule({
      imports: [RadarColumnsComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: RadarService, useValue: radar },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
      ],
    });
    fixture = TestBed.createComponent(RadarColumnsComponent);
    component = fixture.componentInstance;
    changed = 0;
    component.changed.subscribe(() => changed++);
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const clean = (value: string | null | undefined) => (value ?? '').replace(/\s+/g, ' ').trim();

  it('trois panneaux avec leur compte, les engagements dus portent le filet, les pastilles sont écrites', () => {
    const root = build();

    expect(radar.board).toHaveBeenCalledOnceWith('h1');
    const heads = Array.from(root.querySelectorAll('.radar-columns__hd')).map((h) => clean(h.querySelector('h4')?.textContent)
      + ' ' + clean(h.querySelector('.radar-columns__n')?.textContent));
    expect(heads).toEqual(['À faire par moi 2', 'Sujets en cours 3', "J'attends des autres 1"]);

    const todo = root.querySelectorAll('.radar-columns__todo .radar-columns__item');
    expect(todo[0].classList).toContain('radar-columns__item--due');
    expect(clean(todo[0].textContent)).toContain('en retard de 3 j');
    expect(todo[0].querySelector('.radar-columns__open')?.getAttribute('rel')).toBe('noopener noreferrer');
    expect(todo[0].querySelector('a[href="/vigie/h1/sujets/s1"]')).not.toBeNull();
    expect(todo[1].classList).not.toContain('radar-columns__item--due');

    const waiting = root.querySelector('.radar-columns__waiting .radar-columns__item') as HTMLElement;
    expect(clean(waiting.querySelector('.radar-columns__t')?.textContent)).toBe("Julie Robert — Retour de l'éditeur SSO");
    expect(clean(waiting.textContent)).toContain('relance due');
    expect(waiting.querySelector('.radar-columns__done')?.textContent).toContain('Reçu');
    expect(waiting.querySelector('.radar-columns__src')?.textContent).toContain('mail');
  });

  it('un probable est une question : C\'est moi / Pas moi, rien d\'autre', () => {
    const root = build();
    const question = root.querySelectorAll('.radar-columns__todo .radar-columns__item')[1] as HTMLElement;

    expect(clean(question.querySelector('.radar-columns__t')?.textContent)).toBe('Rédiger la note DSI ?');
    expect(clean(question.textContent)).toContain('probable');
    expect(question.querySelector('.radar-columns__done')).toBeNull();
    expect(question.querySelector('.radar-columns__postpone')).toBeNull();

    (question.querySelector('.radar-columns__confirm') as HTMLButtonElement).click();
    expect(radar.correctCommitment).toHaveBeenCalledWith('h1', 'c2', 'CONFIRM');
  });

  it('Fait : la correction, la snackbar Annuler, puis colonnes et résumé relus ; Annuler défait', () => {
    const root = build();

    (root.querySelector('.radar-columns__todo .radar-columns__done') as HTMLButtonElement).click();
    expect(radar.correctCommitment).toHaveBeenCalledWith('h1', 'c1', 'DONE');
    expect(snackBar.open).toHaveBeenCalledWith('Marqué fait.', 'Annuler', jasmine.any(Object));
    expect(radar.board).toHaveBeenCalledTimes(2);
    expect(changed).toBe(1);

    snackAction.next();
    expect(radar.undo).toHaveBeenCalledWith('h1', 'corr1');
    expect(radar.board).toHaveBeenCalledTimes(3);
    expect(changed).toBe(2);
  });

  it('Pas moi et Reporter (la date calculée)', () => {
    const root = build();

    (root.querySelector('.radar-columns__todo .radar-columns__not-mine') as HTMLButtonElement).click();
    expect(radar.correctCommitment).toHaveBeenCalledWith('h1', 'c1', 'NOT_MINE');

    component.postpone(board().toDo[0], 'one-week');
    expect(radar.correctCommitment).toHaveBeenCalledWith('h1', 'c1', 'POSTPONE', postponeDate('one-week'));
  });

  it('Clore un sujet avec des engagements ouverts propose de les fermer aussi', () => {
    const root = build();
    radar.closeSubject.and.returnValue(of({ correction: correction('close1'), openCommitments: [commitment()] }));
    dialogResult = 'DONE';

    (root.querySelector('.radar-columns__close') as HTMLButtonElement).click();
    expect(radar.closeSubject).toHaveBeenCalledWith('h1', 's1');
    expect(dialog.open).toHaveBeenCalledWith(CloseSubjectDialogComponent, jasmine.objectContaining({
      data: { subjectName: 'MFA prestataires', commitments: ['Présenter Sophie à Karim'] },
    }));
    expect(radar.correctCommitment).toHaveBeenCalledWith('h1', 'c1', 'DONE');
    expect(snackBar.open).toHaveBeenCalledWith('« MFA prestataires » est clos.', 'Annuler', jasmine.any(Object));
    expect(changed).toBe(1);
  });

  it('Clore sans engagement ouvert : aucun dialogue ; les laisser ouverts : aucune correction', () => {
    const root = build();
    radar.closeSubject.and.returnValue(of({ correction: correction('close1'), openCommitments: [] }));
    (root.querySelector('.radar-columns__close') as HTMLButtonElement).click();
    expect(dialog.open).not.toHaveBeenCalled();

    radar.closeSubject.and.returnValue(of({ correction: correction('close2'), openCommitments: [commitment()] }));
    dialogResult = 'KEEP';
    component.close(board().subjects[0]);
    expect(dialog.open).toHaveBeenCalledTimes(1);
    expect(radar.correctCommitment).not.toHaveBeenCalled();
  });

  it('clos ? : Clore confirme, Garder ouvert refuse ; réveillé : Rouvrir, Laisser clos', () => {
    const root = build();
    radar.confirmClosure.and.returnValue(of({ correction: correction(), openCommitments: [] }));
    radar.rejectClosure.and.returnValue(of(correction()));
    radar.setSubjectState.and.returnValue(of(correction()));
    radar.dismissWake.and.returnValue(of(correction()));

    const proposed = root.querySelector('[data-state="CLOSE_PROPOSED"]') as HTMLElement;
    expect(clean(proposed.textContent)).toContain('clos ?');
    expect(clean(proposed.textContent)).toContain('Clôture proposée');
    (proposed.querySelector('.radar-columns__confirm-close') as HTMLButtonElement).click();
    expect(radar.confirmClosure).toHaveBeenCalledWith('h1', 's2');
    component.rejectClosure(board().subjects[1]);
    expect(radar.rejectClosure).toHaveBeenCalledWith('h1', 's2');

    fixture.detectChanges();
    const awake = root.querySelector('[data-state="CLOSED"]') as HTMLElement;
    expect(clean(awake.textContent)).toContain('se réveille');
    (awake.querySelector('.radar-columns__reopen') as HTMLButtonElement).click();
    expect(radar.setSubjectState).toHaveBeenCalledWith('h1', 's3', 'ADVANCING');
    component.dismissWake(board().subjects[2]);
    expect(radar.dismissWake).toHaveBeenCalledWith('h1', 's3');
  });

  it("« À traiter d'abord » range les sujets ; les personnes sont écrites, sans pastille de couleur", () => {
    const root = build();
    const names = () => Array.from(root.querySelectorAll('.radar-columns__nm')).map((n) => n.textContent?.trim());
    expect(names()).toEqual(['MFA prestataires', 'Migration LDAP', 'Annuaire']);

    component.setOrder('attention');
    fixture.detectChanges();
    expect(names()).toEqual(['Annuaire', 'Migration LDAP', 'MFA prestataires']);
    expect(clean(root.querySelector('.radar-columns__people')?.textContent)).toContain('Paul Martin, Sophie Laurent');
  });

  it('un refus de la gateway (409) se dit, et les colonnes sont relues', () => {
    const root = build();
    radar.correctCommitment.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { error: 'radar_subject_merged', message: 'Ce sujet a été fusionné.' } })));

    (root.querySelector('.radar-columns__todo .radar-columns__done') as HTMLButtonElement).click();
    expect(snackBar.open).toHaveBeenCalledWith('Ce sujet a été fusionné.', 'Fermer',
      jasmine.objectContaining({ panelClass: 'snack-error' }));
    expect(radar.board).toHaveBeenCalledTimes(2);
    expect(changed).toBe(0);
  });

  it('§17 : les pastilles emploient la charte — bleu §9 index 0 pour « clos ? », rouge §5, filet orange du dû', () => {
    const root = build();
    const style = (el: Element | null) => getComputedStyle(el as Element);

    const proposed = root.querySelector('[data-state="CLOSE_PROPOSED"] .radar-state');
    expect(style(proposed).color).toBe('rgb(56, 101, 153)');
    expect(style(proposed).backgroundColor).toBe('rgb(231, 239, 249)');

    const overdue = root.querySelector('.radar-columns__todo .radar-columns__item .radar-state');
    expect(overdue?.textContent).toContain('en retard');
    expect(style(overdue).backgroundColor).toBe('rgb(255, 235, 238)');

    const due = root.querySelector('.radar-columns__item--due');
    expect(style(due).boxShadow).toContain('4px 0px 0px 0px');
  });

  it('colonnes vides : chacune dit ce qui manque ; illisibles : Réessayer', () => {
    let root = build({ toDo: [], subjects: [], waiting: [] });
    const empties = Array.from(root.querySelectorAll('.radar-columns__empty')).map((e) => e.textContent?.trim());
    expect(empties).toEqual(['Rien à faire pour vous.', 'Aucun sujet suivi : ils apparaissent à la première synchro.',
      "Rien n'est attendu des autres."]);

    TestBed.resetTestingModule();
    root = build(new Error('réseau'));
    expect(clean(root.querySelector('.radar-columns__error')?.textContent)).toContain("Les colonnes n'ont pas pu être lues.");
  });
});
