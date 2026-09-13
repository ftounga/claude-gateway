import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, ParamMap, Router, convertToParamMap, provideRouter } from '@angular/router';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarRef, TextOnlySnackBar } from '@angular/material/snack-bar';
import { BehaviorSubject, Observable, Subject, of, throwError } from 'rxjs';

import { WorkspaceDetail } from '../../core/models/atelier.models';
import {
  RadarCorrectionView,
  RadarEvidenceView,
  RadarManagerAnswer,
  RadarSubjectDetail,
  RadarSubjectProjects,
  RadarUnknownView,
} from '../../core/models/radar-subject.models';
import { VigiePerson } from '../../core/models/vigie.models';
import { AtelierService } from '../../core/services/atelier.service';
import { RadarSubjectService } from '../../core/services/radar-subject.service';
import { VigieService } from '../../core/services/vigie.service';
import {
  RadarSubjectPageComponent,
  adjustDraft,
  answerErrorOf,
  errorOf,
} from './radar-subject-page.component';
import { subjectDetail } from './radar-subject.fixtures';

/** La page d'un sujet du Radar (F-103 / SF-103-01). */
describe('RadarSubjectPageComponent', () => {
  let fixture: ComponentFixture<RadarSubjectPageComponent>;
  let component: RadarSubjectPageComponent;
  let subjects: jasmine.SpyObj<RadarSubjectService>;
  let vigie: jasmine.SpyObj<VigieService>;
  let params$: BehaviorSubject<ParamMap>;
  let atelier: jasmine.SpyObj<AtelierService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let snackAction$: Subject<void>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let router: Router;

  const prepared: RadarManagerAnswer = {
    text: 'Le périmètre MFA est validé ; le pilote part en octobre.', preparedAt: '2026-09-13T10:00:00Z',
    coverageIncomplete: false, unknownsCount: 1,
  };

  const evidence = (id: string, extra: Partial<RadarEvidenceView> = {}): RadarEvidenceView => ({
    id, source: 'TEAMS_MESSAGE', sourceRef: `ref-${id}`, occurredAt: '2026-09-04T09:00:00Z',
    quote: `citation ${id}`, deepLink: `https://teams.microsoft.com/l/message/${id}`, authorPersonId: null,
    ...extra,
  });

  const mfa = (): RadarSubjectDetail => subjectDetail({
    name: 'MFA prestataires',
    state: 'ADVANCING',
    nextStep: 'Rédiger la note DSI',
    dueDate: '2026-10-15',
    dueDateSovereign: true,
    lastActivityAt: '2026-09-12T14:32:10Z',
    stateEvidenceIds: ['p2'],
    nextStepEvidenceIds: ['p2'],
    dueDateEvidenceIds: ['p3'],
    summary: [
      { id: 'f1', position: 0, text: 'Le périmètre couvre 340 comptes.', evidenceIds: ['p1'] },
      { id: 'f2', position: 1, text: 'Paul l\'a validé en réunion.', evidenceIds: ['p2'] },
    ],
    chronology: [
      evidence('p3', { source: 'USER_NOTE', occurredAt: '2026-09-13T08:00:00Z', deepLink: null }),
      evidence('p2', { source: 'TEAMS_MEETING', occurredAt: '2026-09-12T14:32:10Z', authorPersonId: 'paul' }),
      evidence('p1', { deepLink: 'javascript:alert(1)' }),
    ],
  });

  const noProjects: RadarSubjectProjects = { inForge: true, links: [], candidates: [] };

  const withProjects = (): RadarSubjectProjects => ({
    inForge: true,
    links: [
      { workspaceId: 'w1', name: 'billing', projectPath: 'clients/billing-api', origin: 'PROPOSED', state: 'PROPOSED' },
      { workspaceId: 'w2', name: 'infra', projectPath: 'infra', origin: 'USER', state: 'CONFIRMED' },
    ],
    candidates: [{ workspaceId: 'w3', name: 'web', projectPath: null }],
  });

  const paul: VigiePerson = { id: 'paul', displayName: 'Paul Martin', jobTitle: 'Manager sécurité',
    lastInteractionAt: null, subjects: [] };

  function build(options: {
    subject?: Observable<RadarSubjectDetail>;
    people?: Observable<VigiePerson[]>;
    unknowns?: Observable<RadarUnknownView[]>;
    answer?: Observable<RadarManagerAnswer>;
    terminal?: Observable<WorkspaceDetail>;
    subjectId?: string;
    projects?: Observable<RadarSubjectProjects>;
    corrections?: Observable<RadarCorrectionView[]>;
  } = {}): HTMLElement {
    subjects = jasmine.createSpyObj<RadarSubjectService>('RadarSubjectService',
      ['subject', 'unknowns', 'managerAnswer', 'undoNews', 'projects', 'linkProject', 'unlinkProject',
        'corrections', 'split', 'addAlias', 'removeAlias', 'undo']);
    subjects.projects.and.returnValue(options.projects ?? of(noProjects));
    subjects.undoNews.and.returnValue(of({ evidenceId: 'p3', undone: 1 }));
    subjects.corrections.and.returnValue(options.corrections ?? of([]));
    subjects.subject.and.returnValue(options.subject ?? of(mfa()));
    subjects.unknowns.and.returnValue(options.unknowns ?? of([]));
    subjects.managerAnswer.and.returnValue(options.answer ?? of(prepared));
    atelier = jasmine.createSpyObj<AtelierService>('AtelierService', ['openTeamsTerminal']);
    atelier.openTeamsTerminal.and.returnValue(options.terminal ?? of({ id: 'wtt1', name: 'Terminal Teams' } as WorkspaceDetail));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    snackAction$ = new Subject<void>();
    snackBar.open.and.returnValue({ onAction: () => snackAction$.asObservable() } as unknown as MatSnackBarRef<TextOnlySnackBar>);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    vigie = jasmine.createSpyObj<VigieService>('VigieService', ['people', 'hostSpaces']);
    vigie.people.and.returnValue(options.people ?? of([paul]));
    vigie.hostSpaces.and.returnValue(of([{ hostId: 'h1', name: 'EDENRED', missionStatus: 'ACTIVE', spaces: ['VIGIE'] }]));
    params$ = new BehaviorSubject(convertToParamMap({ hostRef: 'h1', subjectId: options.subjectId ?? 's1' }));

    TestBed.configureTestingModule({
      imports: [RadarSubjectPageComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: RadarSubjectService, useValue: subjects },
        { provide: VigieService, useValue: vigie },
        { provide: AtelierService, useValue: atelier },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
        { provide: ActivatedRoute, useValue: { snapshot: {}, paramMap: params$, queryParamMap: of(convertToParamMap({})) } },
      ],
    });
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(RadarSubjectPageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it("lit le sujet sous le poste de l'adresse, et rend l'en-tête et les trois cases", () => {
    const root = build();

    expect(subjects.subject).toHaveBeenCalledOnceWith('h1', 's1');
    const crumbs = Array.from(root.querySelectorAll('.radar-subject__crumb a')) as HTMLAnchorElement[];
    expect(crumbs.map((a) => text(a))).toEqual(['EDENRED', 'Radar']);
    expect(crumbs.map((a) => a.getAttribute('href'))).toEqual(['/vigie/h1', '/vigie/h1?onglet=radar']);
    expect(text(root.querySelector('.radar-subject__title'))).toBe('MFA prestataires');
    const badge = root.querySelector('.radar-subject__state');
    expect(text(badge)).toBe('avance');
    expect(badge?.classList).toContain('radar-state--success');
    expect(text(root.querySelector('.radar-subject__next-step'))).toBe('Rédiger la note DSI');
    expect(text(root.querySelector('.radar-subject__due'))).toBe('15 octobre 2026');
    expect(text(root.querySelector('.radar-subject__facts'))).toContain('votre note');
  });

  it('rend chaque phrase avec ses renvois, le même numéro que dans la chronologie', () => {
    const root = build();

    const sentences = root.querySelectorAll('.radar-subject__sentence');
    expect(sentences.length).toBe(2);
    const firstRef = sentences[0].querySelector('.radar-subject__ref');
    expect(text(firstRef)).toBe('1');
    expect(firstRef?.getAttribute('href')).toBe('#preuve-p1');
    expect(sentences[1].querySelector('.radar-subject__ref')?.getAttribute('href')).toBe('#preuve-p2');

    const events = root.querySelectorAll('.radar-subject__event');
    expect(events.length).toBe(3);
    // Plus récente d'abord, dans l'ordre de la gateway ; la note de l'échéance reçoit le numéro 3.
    expect(events[0].id).toBe('preuve-p3');
    expect(text(events[0].querySelector('.radar-subject__event-number'))).toBe('3');
    expect(text(events[1].querySelector('.radar-subject__event-number'))).toBe('2');
  });

  it("nomme l'auteur connu, libelle le lien selon la source, et n'affiche jamais un lien non https", () => {
    const root = build();
    const events = root.querySelectorAll('.radar-subject__event');

    expect(text(events[0])).toContain('Votre nouvelle');
    expect(events[0].querySelector('.radar-subject__deep-link')).toBeNull();

    expect(text(events[1].querySelector('.radar-subject__event-author'))).toBe('— Paul Martin');
    const meetingLink = events[1].querySelector('a.radar-subject__deep-link') as HTMLAnchorElement;
    expect(text(meetingLink)).toContain('Ouvrir la source ·');
    expect(meetingLink.getAttribute('rel')).toBe('noopener noreferrer');
    expect(meetingLink.getAttribute('target')).toBe('_blank');

    expect(text(events[2].querySelector('.radar-subject__quote'))).toBe('citation p1');
    expect(events[2].querySelector('.radar-subject__deep-link')).toBeNull();
  });

  it('un renvoi met la preuve en évidence sans quitter la page', () => {
    const root = build();
    const ref = root.querySelector('.radar-subject__sentence .radar-subject__ref') as HTMLAnchorElement;
    const event = new MouseEvent('click', { cancelable: true });

    ref.dispatchEvent(event);
    fixture.detectChanges();

    expect(event.defaultPrevented).toBeTrue();
    expect(component.focusedEvidenceId()).toBe('p1');
    expect(root.querySelector('#preuve-p1')?.classList).toContain('radar-subject__event--focused');
  });

  it("un annuaire illisible n'empêche pas la page : l'auteur n'est simplement pas nommé", () => {
    const root = build({ people: throwError(() => new HttpErrorResponse({ status: 500 })) });

    expect(root.querySelector('.radar-subject__title')).not.toBeNull();
    expect(root.querySelector('.radar-subject__event-author')).toBeNull();
  });

  it("résumé vide et prochaine étape inconnue : la page le dit", () => {
    const root = build({ subject: of(subjectDetail({ name: 'LDAP' })) });

    expect(text(root.querySelector('.radar-subject__summary-empty'))).toContain("il s'écrit à la prochaine analyse");
    expect(root.querySelectorAll('.radar-subject__unknown').length).toBe(2);
  });

  it('un sujet fusionné mène au sujet cible', () => {
    const root = build({ subject: of(subjectDetail({ mergedIntoId: 's9' })) });

    const link = root.querySelector('.radar-subject__merged a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/vigie/h1/sujets/s9');
  });

  it('« clos ? » : le bandeau cite le signal de clôture', () => {
    const root = build({ subject: of(subjectDetail({
      state: 'CLOSE_PROPOSED', closeSignalEvidenceIds: ['p7'], chronology: [evidence('p7')],
    })) });

    const banner = root.querySelector('.radar-subject__close-proposed');
    expect(text(banner)).toContain('Le Radar pense que ce sujet est terminé');
    expect(banner?.querySelector('.radar-subject__ref')?.getAttribute('href')).toBe('#preuve-p7');
    expect(root.querySelector('.radar-subject__state')?.classList).toContain('radar-state--blue');
  });

  it('en sommeil : le bandeau le dit sans rien clore', () => {
    const root = build({ subject: of(subjectDetail({ state: 'DORMANT', lastActivityAt: '2026-08-20T10:00:00Z' })) });

    expect(text(root.querySelector('.radar-subject__dormant'))).toContain('Rien n\'a bougé depuis le 20 août 2026');
  });

  it('introuvable (404) : rien d\'autre n\'est dit, et le Radar du client est à un clic', () => {
    const root = build({ subject: throwError(() => new HttpErrorResponse({ status: 404 })) });

    const notice = root.querySelector('.radar-subject__notice');
    expect(notice?.getAttribute('data-error')).toBe('not-found');
    expect(text(notice)).toContain("Ce sujet n'existe pas, ou plus");
    expect(notice?.querySelector('a')?.getAttribute('href')).toBe('/vigie/h1?onglet=radar');
    expect(root.querySelector('.radar-subject__title')).toBeNull();
  });

  it('client hors Vigie (409), sans droit (403) : les bons écrans', () => {
    let root = build({ subject: throwError(() => new HttpErrorResponse({ status: 409 })) });
    expect(text(root.querySelector('.radar-subject__notice'))).toContain("Ce client n'est pas dans la Vigie");

    TestBed.resetTestingModule();
    root = build({ subject: throwError(() => new HttpErrorResponse({ status: 403 })) });
    expect(root.querySelector('app-space-pitch')).not.toBeNull();
    expect(root.querySelector('.radar-subject__notice')).toBeNull();
  });

  it('gateway injoignable : Réessayer relit le sujet', () => {
    let calls = 0;
    const root = build({
      subject: new Observable<RadarSubjectDetail>((subscriber) => {
        calls += 1;
        if (calls === 1) {
          subscriber.error(new HttpErrorResponse({ status: 0 }));
        } else {
          subscriber.next(mfa());
          subscriber.complete();
        }
      }),
    });

    expect(root.querySelector('.radar-subject__notice')?.getAttribute('data-error')).toBe('network');
    (root.querySelector('.radar-subject__retry') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(root.querySelector('.radar-subject__title')).not.toBeNull();
  });

  it("changer de sujet dans l'adresse relit la page", () => {
    build();
    params$.next(convertToParamMap({ hostRef: 'h1', subjectId: 's2' }));

    expect(subjects.subject).toHaveBeenCalledWith('h1', 's2');
    // Le nom du client n'est lu qu'une fois par client.
    expect(vigie.hostSpaces).toHaveBeenCalledTimes(1);
  });

  // ---- SF-103-02 : qui, et à qui demander ----

  it('rend les personnes rangées par rôle, le rôle écrit', () => {
    const role = (id: string, displayName: string, r: 'DECIDES' | 'DRIVES' | 'EXPERT' | 'INFORMED') =>
      ({ id, personId: `p-${id}`, displayName, jobTitle: id === 'r2' ? 'Cheffe de projet IAM' : null, role: r, evidenceIds: ['p2'] });
    const root = build({ subject: of(subjectDetail({
      people: [role('r1', 'Karim', 'EXPERT'), role('r2', 'Sophie', 'DRIVES'), role('r3', 'Paul', 'DECIDES')],
      chronology: [evidence('p2')],
    })) });

    const persons = Array.from(root.querySelectorAll('.radar-subject__person'));
    expect(persons.map((p) => text(p.querySelector('.radar-subject__person-name')))).toEqual(['Paul', 'Sophie', 'Karim']);
    expect(persons.map((p) => text(p.querySelector('.radar-subject__role')))).toEqual(['décide', 'pilote', 'expert']);
    expect(text(persons[1].querySelector('.radar-subject__person-job'))).toBe('Cheffe de projet IAM');
    expect(persons[0].querySelector('.radar-subject__ref')?.getAttribute('href')).toBe('#preuve-p2');
    // SF-103-04 : l'annuaire du client est à un clic.
    expect(root.querySelector('.radar-subject__directory-link')?.getAttribute('href')).toBe('/vigie/h1?onglet=personnes');
  });

  it('dit ce que le Radar ne sait pas, et à qui le demander', () => {
    const root = build({ unknowns: of([
      { kind: 'COVERAGE', question: "La dernière synchro n'a pas tout lu : ce qui précède peut être incomplet.",
        ask: null, evidenceIds: [], commitmentId: null },
      { kind: 'NEXT_STEP', question: "La prochaine étape n'est pas connue.",
        ask: { personId: 'sophie', displayName: 'Sophie Laurent', jobTitle: 'Cheffe de projet IAM', role: 'DRIVES',
          reason: 'pilote le sujet' }, evidenceIds: [], commitmentId: null },
      { kind: 'DECIDER', question: 'On ne sait pas qui décide.', ask: null, evidenceIds: [], commitmentId: null },
    ] as RadarUnknownView[]) });

    expect(subjects.unknowns).toHaveBeenCalledOnceWith('h1', 's1');
    const gaps = root.querySelectorAll('.radar-subject__gap');
    expect(gaps.length).toBe(3);
    expect(gaps[0].querySelector('.radar-subject__ask')).toBeNull();
    expect(text(gaps[1].querySelector('.radar-subject__ask')))
      .toBe('À qui demander : Sophie Laurent, Cheffe de projet IAM — pilote le sujet.');
    expect(text(gaps[2].querySelector('.radar-subject__ask--nobody'))).toBe("Personne n'est identifié sur ce sujet.");
  });

  it("des manques illisibles n'empêchent pas la page ; aucun manque est dit aussi", () => {
    let root = build({ unknowns: throwError(() => new HttpErrorResponse({ status: 500 })) });
    expect(root.querySelector('.radar-subject__title')).not.toBeNull();
    expect(text(root.querySelector('.radar-subject__unknowns-error'))).toContain("n'a pas pu être lu");

    TestBed.resetTestingModule();
    root = build();
    expect(root.querySelector('.radar-subject__unknowns-empty')).not.toBeNull();
    expect(text(root.querySelector('.radar-subject__people-empty'))).toContain('Personne');
  });

  // ---- SF-103-03 : la réponse au manager ----

  it("rien n'est préparé à l'ouverture : la réponse attend un geste", () => {
    const root = build();

    expect(subjects.managerAnswer).not.toHaveBeenCalled();
    expect(root.querySelector('.radar-subject__answer-text')).toBeNull();
    expect(text(root.querySelector('.radar-subject__answer'))).toContain('elle compte dans votre consommation');
  });

  it('Préparer la réponse affiche le texte ; Copier le copie', async () => {
    const root = build();
    (root.querySelector('.radar-subject__prepare') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(subjects.managerAnswer).toHaveBeenCalledOnceWith('h1', 's1');
    expect(text(root.querySelector('.radar-subject__answer-text'))).toBe(prepared.text);
    expect(root.querySelector('.radar-subject__answer-warning')).toBeNull();

    const writeText = jasmine.createSpy('writeText').and.resolveTo();
    // Même procédé que les autres specs : d'autres suites ont déjà remplacé la propriété par une valeur.
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    (root.querySelector('.radar-subject__copy') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(writeText).toHaveBeenCalledOnceWith(prepared.text);
    expect(snackBar.open).toHaveBeenCalledWith('Réponse copiée.', 'Fermer', jasmine.anything());
  });

  it('Ajuster en discutant ouvre la conversation du client avec le brouillon, sans rien envoyer', () => {
    const root = build();
    component.prepareAnswer();
    fixture.detectChanges();

    (root.querySelector('.radar-subject__adjust') as HTMLButtonElement).click();

    expect(atelier.openTeamsTerminal).toHaveBeenCalledOnceWith('h1');
    expect(router.navigate).toHaveBeenCalledOnceWith(['/atelier', 'wtt1'], {
      state: { radarDraft: adjustDraft('MFA prestataires', prepared.text) },
    });
    expect(adjustDraft('MFA', 'OK.'))
      .toBe('Aide-moi à ajuster la réponse que je vais donner à mon manager sur le sujet « MFA » :\n\nOK.');
  });

  it('conversation impossible à ouvrir : la page le dit, sans naviguer', () => {
    build({ terminal: throwError(() => new HttpErrorResponse({ status: 500 })) });
    component.prepareAnswer();
    component.adjustAnswer();

    expect(router.navigate).not.toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('couverture incomplète : la réponse le rappelle', () => {
    const root = build({ answer: of({ ...prepared, coverageIncomplete: true }) });
    component.prepareAnswer();
    fixture.detectChanges();

    expect(text(root.querySelector('.radar-subject__answer-warning'))).toContain("n'a pas tout lu");
  });

  it('quota atteint (402) ou fournisseur indisponible (503) : la cause est dite', () => {
    const root = build({ answer: throwError(() => new HttpErrorResponse({ status: 402 })) });
    component.prepareAnswer();
    fixture.detectChanges();

    expect(text(root.querySelector('.radar-subject__answer-error'))).toContain('quota de consommation est atteint');
    expect(root.querySelector('.radar-subject__prepare')).not.toBeNull();
    expect(answerErrorOf(new HttpErrorResponse({ status: 503 }))).toContain('momentanément indisponible');
    expect(answerErrorOf(new HttpErrorResponse({ status: 502 }))).toContain("n'a pas pu être préparée");
  });

  it('traduit les codes HTTP', () => {
    expect(errorOf(new HttpErrorResponse({ status: 400 }))).toBe('not-found');
    expect(errorOf(new HttpErrorResponse({ status: 503 }))).toBe('network');
    expect(errorOf(new Error('x'))).toBe('network');
  });

  // ------------------------------------------------------------ F-104 / SF-104-02 : annuler une nouvelle

  it('une note ou un courriel collé porte « Annuler cette nouvelle » ; un message Teams non', () => {
    const root = build();

    const buttons = Array.from(root.querySelectorAll('.radar-subject__undo-news'));
    expect(buttons.length).toBe(1);
    expect(root.querySelector('#preuve-p3 .radar-subject__undo-news')).not.toBeNull();
    expect(root.querySelector('#preuve-p2 .radar-subject__undo-news')).toBeNull();
  });

  it('annuler la nouvelle : appel sous le poste, snackbar, page relue', () => {
    const root = build();
    subjects.subject.calls.reset();

    (root.querySelector('#preuve-p3 .radar-subject__undo-news') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(subjects.undoNews).toHaveBeenCalledOnceWith('h1', 'p3');
    expect(snackBar.open).toHaveBeenCalledWith('Nouvelle annulée : le Radar a tout défait.', 'Fermer', jasmine.any(Object));
    expect(subjects.subject).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it("annuler la nouvelle qui avait créé le sujet : retour au Radar du client ; conflit : dit, rien d'annulé", () => {
    build();
    subjects.subject.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    component.undoNews(mfa().chronology[0]);
    expect(router.navigate).toHaveBeenCalledWith(['/vigie', 'h1']);

    subjects.undoNews.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409 })));
    component.undoNews(mfa().chronology[0]);
    expect(snackBar.open).toHaveBeenCalledWith(jasmine.stringContaining('Rien n’a été annulé'), 'Fermer', jasmine.any(Object));
  });

  // ------------------------------------------------ le projet dans la Forge (F-106 / SF-106-06)

  it('le projet dans la Forge : une proposition en question, un lien confirmé avec sa passerelle, le menu des projets', () => {
    const root = build({ projects: of(withProjects()) });

    expect(subjects.projects).toHaveBeenCalledOnceWith('h1', 's1');
    expect(text(root.querySelector('.radar-subject__proposal-question')))
      .toContain('Ce sujet concerne-t-il le projet billing (clients/billing-api) ?');
    const linked = root.querySelectorAll('.radar-subject__linked-project');
    expect(linked.length).toBe(1);
    expect(text(linked[0])).toContain('infra');
    expect(linked[0].querySelector('.radar-subject__forge-link')?.getAttribute('href')).toBe('/forge/h1');
    expect(root.querySelector('.radar-subject__link-project')).not.toBeNull();
    expect(root.querySelector('.radar-subject__projects-empty')).toBeNull();
  });

  it('« Oui, lier » confirme, « Non » refuse, « Délier » défait : la section suit la réponse', () => {
    const root = build({ projects: of(withProjects()) });
    const confirmed: RadarSubjectProjects = {
      inForge: true,
      links: [
        { workspaceId: 'w1', name: 'billing', projectPath: 'clients/billing-api', origin: 'PROPOSED', state: 'CONFIRMED' },
        { workspaceId: 'w2', name: 'infra', projectPath: 'infra', origin: 'USER', state: 'CONFIRMED' },
      ],
      candidates: [{ workspaceId: 'w3', name: 'web', projectPath: null }],
    };
    subjects.linkProject.and.returnValue(of(confirmed));

    (root.querySelector('.radar-subject__proposal-yes') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(subjects.linkProject).toHaveBeenCalledOnceWith('h1', 's1', 'w1');
    expect(root.querySelector('.radar-subject__proposal')).toBeNull();
    expect(root.querySelectorAll('.radar-subject__linked-project').length).toBe(2);

    subjects.unlinkProject.and.returnValue(of(noProjects));
    (root.querySelector('.radar-subject__unlink') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(subjects.unlinkProject).toHaveBeenCalledOnceWith('h1', 's1', 'w1');
    expect(text(root.querySelector('.radar-subject__projects-empty'))).toBe('Aucun projet de la Forge sur ce poste.');
  });

  it('client absent de la Forge : pas de « Voir le projet dans la Forge » ; lecture en échec : dit, la page reste', () => {
    let root = build({ projects: of({ ...withProjects(), inForge: false }) });
    expect(root.querySelector('.radar-subject__forge-link')).toBeNull();

    TestBed.resetTestingModule();
    root = build({ projects: throwError(() => new HttpErrorResponse({ status: 500 })) });
    expect(root.querySelector('.radar-subject__projects-error')).not.toBeNull();
    expect(text(root.querySelector('.radar-subject__title'))).toBe('MFA prestataires');
  });

  it('un lien refusé par la gateway : dit, rien ne change', () => {
    const root = build({ projects: of(withProjects()) });
    subjects.linkProject.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

    component.linkProject('w3');
    fixture.detectChanges();

    expect(snackBar.open).toHaveBeenCalledWith(jasmine.any(String), 'Fermer', jasmine.any(Object));
    expect(root.querySelectorAll('.radar-subject__linked-project').length).toBe(1);
    expect(component.projectBusy()).toBeNull();
  });

  // ------------------------------------------------------------ séparer, alias, journal (F-99 / SF-99-06)

  const correction = (extra: Partial<RadarCorrectionView>): RadarCorrectionView => ({
    id: 'c1', subjectId: 's1', targetKind: 'SUBJECT', targetId: 's1', action: 'ADD_ALIAS', before: {},
    after: { aliasId: 'a1', alias: 'Chantier Okta' }, createdAt: '2026-09-13T09:00:00Z', undoneAt: null, ...extra,
  });

  it('lit le journal du sujet et le rend dans la chronologie, avec Annuler', () => {
    const root = build({ corrections: of([correction({}), correction({ id: 'c0', undoneAt: '2026-09-13T09:05:00Z' })]) });

    expect(subjects.corrections).toHaveBeenCalledWith('h1', 's1');
    const items = root.querySelectorAll('.subject-journal__item');
    expect(items.length).toBe(2);
    expect(text(items[0])).toContain('Alias ajouté : « Chantier Okta »');
    expect(items[0].querySelector('.subject-journal__undo')).not.toBeNull();
    expect(items[1].querySelector('.subject-journal__undo')).toBeNull();
    expect(text(items[1])).toContain('annulé');
  });

  it("Annuler une correction du journal : appel sous le poste, page relue ; un conflit est dit", () => {
    const root = build({ corrections: of([correction({})]) });
    subjects.undo.and.returnValue(of(correction({ undoneAt: '2026-09-13T10:00:00Z' })));
    subjects.subject.calls.reset();

    (root.querySelector('.subject-journal__undo') as HTMLButtonElement).click();

    expect(subjects.undo).toHaveBeenCalledOnceWith('h1', 'c1');
    expect(snackBar.open).toHaveBeenCalledWith('Geste annulé.', 'Fermer', jasmine.any(Object));
    expect(subjects.subject).toHaveBeenCalled();

    subjects.undo.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { error: 'radar_correction_conflict', message: 'Cet alias a déjà été retiré.' } })));
    component.undoCorrection(correction({}));
    expect(snackBar.open).toHaveBeenCalledWith('Cet alias a déjà été retiré.', 'Fermer', jasmine.any(Object));
  });

  it('journal illisible : la page reste, le manque est dit', () => {
    const root = build({ corrections: throwError(() => new HttpErrorResponse({ status: 500 })) });

    expect(root.querySelector('.subject-journal__error')).not.toBeNull();
    expect(root.querySelector('.radar-subject__title')).not.toBeNull();
  });

  it('rend les alias et les consignes ; ajouter appelle la gateway, et Annuler retrouve la correction', () => {
    const root = build({ subject: of(subjectDetail({ aliases: [
      { id: 'a0', alias: 'Double auth', origin: 'MERGE', rejected: false },
      { id: 'a9', alias: 'Contrat Okta', origin: 'SPLIT', rejected: true },
    ] })) });
    expect(text(root.querySelector('.subject-aliases__accepted'))).toContain('Double auth');
    expect(text(root.querySelector('.subject-aliases__rejected'))).toContain('Contrat Okta');

    subjects.addAlias.and.returnValue(of({ id: 'a1', alias: 'Chantier Okta', origin: 'USER', rejected: false }));
    subjects.corrections.and.returnValue(of([correction({})]));
    subjects.undo.and.returnValue(of(correction({ undoneAt: '2026-09-13T10:00:00Z' })));
    component.addAlias('Chantier Okta');

    expect(subjects.addAlias).toHaveBeenCalledOnceWith('h1', 's1', 'Chantier Okta');
    expect(snackBar.open).toHaveBeenCalledWith('Alias ajouté.', 'Annuler', jasmine.any(Object));
    snackAction$.next();
    expect(subjects.undo).toHaveBeenCalledOnceWith('h1', 'c1');
  });

  it('retirer une consigne : appel, snackbar « Consigne retirée. » ; un refus est dit', () => {
    build();
    subjects.removeAlias.and.returnValue(of(undefined));
    component.removeAlias({ id: 'a9', alias: 'Contrat Okta', origin: 'SPLIT', rejected: true });
    expect(subjects.removeAlias).toHaveBeenCalledOnceWith('h1', 's1', 'a9');
    expect(snackBar.open).toHaveBeenCalledWith('Consigne retirée.', 'Annuler', jasmine.any(Object));

    subjects.addAlias.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { error: 'radar_invalid', message: 'Ce nom est déjà connu de ce sujet.' } })));
    component.addAlias('MFA');
    expect(snackBar.open).toHaveBeenCalledWith('Ce nom est déjà connu de ce sujet.', 'Fermer', jasmine.any(Object));
  });

  it('un sujet fusionné ne propose ni Séparer, ni geste sur ses alias', () => {
    const root = build({ subject: of(subjectDetail({ mergedIntoId: 's2',
      aliases: [{ id: 'a0', alias: 'Double auth', origin: 'MERGE', rejected: false }] })) });

    expect(root.querySelector('.radar-subject__split')).toBeNull();
    expect(root.querySelector('.subject-aliases__remove')).toBeNull();
    expect(root.querySelector('.subject-aliases__open')).toBeNull();
  });

  it('Séparer ouvre le dialogue ; une séparation faite propose Annuler et relit la page', () => {
    const root = build();
    const split = correction({ id: 'c7', action: 'SPLIT', after: { source: 's1', created: 's2' } });
    dialog.open.and.returnValue({ afterClosed: () => of({ correction: split, name: 'Contrat Okta' }) } as MatDialogRef<unknown>);
    subjects.undo.and.returnValue(of(split));
    subjects.subject.calls.reset();

    (root.querySelector('.radar-subject__split') as HTMLButtonElement).click();

    expect(dialog.open).toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith('« Contrat Okta » est un nouveau sujet.', 'Annuler', jasmine.any(Object));
    expect(subjects.subject).toHaveBeenCalled();
    snackAction$.next();
    expect(subjects.undo).toHaveBeenCalledOnceWith('h1', 'c7');
  });

  it('Séparer est désactivé sous deux preuves', () => {
    const root = build({ subject: of(subjectDetail({ chronology: [evidence('p1')] })) });
    expect((root.querySelector('.radar-subject__split') as HTMLButtonElement).disabled).toBeTrue();
  });
});
