import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, ParamMap, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';

import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';

import { POSTES_REFRESH_MS, PostesComponent } from './postes.component';
import { AtelierService } from '../core/services/atelier.service';
import { GovernanceService } from '../core/services/governance.service';
import { HostPresenceService } from '../core/services/host-presence.service';
import { GovernanceIntegrite, GovernanceMap } from '../core/models/governance.models';
import { RunnerHostOverview, WorkspaceDetail } from '../core/models/atelier.models';
import { hostInitials, hostTone } from '../shared/host-identity';

/**
 * L'écran des postes (F-49 / SF-49-02) : ce qu'il montre, ce qu'il ne fait pas, et ce qu'il ne
 * casse pas quand le réseau hoquette.
 */
describe('PostesComponent', () => {
  let fixture: ComponentFixture<PostesComponent>;
  let component: PostesComponent;
  let service: jasmine.SpyObj<AtelierService>;
  let governance: jasmine.SpyObj<GovernanceService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  /** Ce que le dialogue de suppression renvoie : `true` = l'utilisateur a confirmé. */
  let dialogAnswer: boolean;
  /** Les paramètres de la route — `/forge/:hostRef` (F-98 / SF-98-01). */
  let params$: BehaviorSubject<ParamMap>;
  /** Les paramètres de requête — `?onglet=` (F-98 / SF-98-02). */
  let query$: BehaviorSubject<ParamMap>;
  /** L'onglet ouvert à la construction de l'écran ; `null` = Projets. */
  let initialTab: string | null;

  const poste: RunnerHostOverview = {
    id: 'h1',
    name: 'Poste CAGIP',
    rootName: 'dev',
    os: 'linux',
    shell: 'posix',
    runnerVersion: '0.0.1',
    elevated: false,
    connected: true,
    lastSeenAt: new Date().toISOString(),
    createdAt: new Date().toISOString(),
    // Cinq minutes, et non « maintenant » : l'écran calcule ses durées À L'AFFICHAGE, et une
    // durée en SECONDES change entre les deux passes de détection du mode développement — ce
    // qu'Angular signale en NG0100, au hasard de la seconde où le test tombe. En minutes, le
    // libellé est stable pendant toute la durée d'un test.
    lastActivityAt: new Date(Date.now() - 300_000).toISOString(),
    activeProjects: 1,
    projects: [
      {
        id: 'w1',
        name: 'web',
        projectPath: 'web',
        executionTarget: 'RUNNER',
        lastActivityAt: new Date(Date.now() - 300_000).toISOString(),
        lastTool: 'bash',
        calls: 12,
        active: true,
      },
      {
        id: 'w2',
        name: 'api',
        projectPath: null,
        executionTarget: 'SANDBOX',
        lastActivityAt: null,
        lastTool: null,
        calls: 0,
        active: false,
      },
    ],
  };

  beforeEach(() => {
    // Par défaut, l'utilisateur n'a rien confirmé : c'est l'état le plus sûr pour un test, et
    // Jasmine tire l'ordre au sort — sans cette remise à zéro, un test en contaminerait un autre.
    dialogAnswer = false;
    initialTab = null;
  });

  function setup(hosts: RunnerHostOverview[] = [poste]): void {
    service = spyService();
    service.runnerHostsOverview.and.returnValue(of(hosts));
    build();
  }

  /**
   * Le service simulé. Depuis F-72 / SF-72-03, l'écran lit aussi la **racine** de chaque poste
   * connecté — une fois par page, jamais au sondage — et peut y **ouvrir** un projet.
   */
  function spyService(): jasmine.SpyObj<AtelierService> {
    const spy = jasmine.createSpyObj<AtelierService>('AtelierService',
      ['runnerHostsOverview', 'setHostMissionStatus', 'deleteRunnerHost', 'runnerHostFolders',
        'openHostProject', 'openHostTerminal', 'createGitWorkspace', 'createWorkspace',
        'killHost', 'teamsAccess', 'openTeamsTerminal']);
    // F-89 / SF-89-03 : le droit Teams, lu UNE FOIS au chargement. Fermé par défaut — les tests
    // qui visent le volet Teams l'ouvrent explicitement, comme un compte qui a souscrit l'option.
    spy.teamsAccess.and.returnValue(of({ entitled: false }));
    spy.openTeamsTerminal.and.returnValue(
      of({ id: 'wtt1', name: 'Terminal Teams', teamsTerminal: true } as WorkspaceDetail));
    // F-82 / SF-82-02 : le coupe-circuit vit désormais sur la carte du poste. Réponse par défaut
    // d'une liaison bien coupée — les tests qui visent un autre chemin la remplacent.
    spy.killHost.and.returnValue(
      of({ revokedTokens: 1, disconnected: true, workspacesReturned: 2 }));
    // F-74 / SF-74-02 : le terminal DU POSTE. L'appel est idempotent côté gateway — elle retrouve
    // ou crée, et répond 200 dans les deux cas.
    spy.openHostTerminal.and.returnValue(
      of({ id: 'wt1', name: 'Terminal du poste', hostTerminal: true } as WorkspaceDetail));
    // F-72 / SF-72-04 : les deux sources SANS MACHINE — dépôt GitHub, archive — ont rejoint la
    // carte « Hébergé », qui est l'endroit juste : elles vivent chez la gateway.
    spy.createGitWorkspace.and.returnValue(of({ id: 'w7', name: 'hello' } as WorkspaceDetail));
    spy.createWorkspace.and.returnValue(of({ id: 'w8', name: 'archive' } as WorkspaceDetail));
    spy.runnerHostFolders.and.returnValue(of({
      path: '',
      parentPath: null,
      folders: [
        { name: 'web', path: 'web', used: true },
        { name: 'EDENRED', path: 'EDENRED', used: false },
      ],
      truncated: false,
    }));
    spy.openHostProject.and.returnValue(of({ id: 'w9', name: 'EDENRED' } as WorkspaceDetail));
    return spy;
  }

  /**
   * La carte du poste (F-92 / SF-92-03) : ce que la machine sait, à sa racine. Relevé nominal —
   * les tests qui visent un autre chemin le remplacent.
   */
  const carte: GovernanceMap = {
    hostRef: 'h1',
    hostId: 'h1',
    hostName: 'Poste CAGIP',
    supported: true,
    governed: true,
    readable: true,
    message: null,
    files: [
      {
        path: 'README.md',
        title: 'La carte du poste',
        present: true,
        readable: true,
        sections: [{ title: 'Contacts', facts: 3 }],
        facts: 3,
        truncated: false,
        message: null,
      },
      {
        path: 'acces.md',
        title: 'Accès',
        present: true,
        readable: true,
        sections: [{ title: 'Les pièges', facts: 0 }],
        facts: 0,
        truncated: false,
        message: null,
      },
    ],
    filesExpected: 2,
    filesPresent: 2,
    sections: 2,
    facts: 3,
  };

  /** Un poste inspecté et sain : deux listes vides, et `inspected` à VRAI. */
  const integriteSaine: GovernanceIntegrite = {
    hostRef: 'h1',
    hostId: 'h1',
    inspected: true,
    errors: [],
    warnings: [],
  };

  /** Ouvre un poste par l'URL, comme `/forge/<ref>` (F-98 / SF-98-01). */
  function openHost(ref: string | null): void {
    params$.next(convertToParamMap(ref ? { hostRef: ref } : {}));
    fixture.detectChanges();
  }

  /** Ouvre un onglet par l'URL, comme `?onglet=<tab>` (F-98 / SF-98-02). */
  function openTab(tab: string | null): void {
    query$.next(convertToParamMap(tab ? { onglet: tab } : {}));
    fixture.detectChanges();
  }

  /** La route simulée : fragment, `hostRef`, `?onglet=`. */
  function routeMock(fragment: string | null): unknown {
    params$ = new BehaviorSubject<ParamMap>(convertToParamMap({}));
    query$ = new BehaviorSubject<ParamMap>(convertToParamMap(initialTab ? { onglet: initialTab } : {}));
    return { snapshot: { fragment }, paramMap: params$, queryParamMap: query$ };
  }

  function build(fragment: string | null = null): void {
    governance = jasmine.createSpyObj<GovernanceService>('GovernanceService',
      ['getMap', 'readMapFile', 'getIntegrite', 'getHosts']);
    governance.getMap.and.returnValue(of(carte));
    governance.getIntegrite.and.returnValue(of(integriteSaine));
    governance.getHosts.and.returnValue(of([]));
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.returnValue({ afterClosed: () => of(dialogAnswer) } as never);
    TestBed.configureTestingModule({
      imports: [PostesComponent],
      providers: [
        { provide: AtelierService, useValue: service },
        { provide: GovernanceService, useValue: governance },
        { provide: MatDialog, useValue: dialog },
        provideRouter([]),
        provideNoopAnimations(),
        // Déclaré APRÈS `provideRouter` : c'est ce jeton-là que l'écran lit pour son ancrage.
        { provide: ActivatedRoute, useValue: routeMock(fragment) },
      ],
    });
    fixture = TestBed.createComponent(PostesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  /** La pastille d'initiales de l'en-tête du poste ouvert (F-98 / SF-98-02). */
  function headMark(): HTMLElement {
    return (fixture.nativeElement as HTMLElement)
      .querySelector('.poste__head .host-badge__mark') as HTMLElement;
  }

  // ------------------------------------------------------------------ rendu

  it('rend une carte par poste, avec son nom et son état', () => {
    setup();
    // `:not(.poste--heberge)` : la carte « Hébergé » est TOUJOURS rendue depuis F-72 / SF-72-04,
    // parce qu'elle porte des gestes. On compte ici les MACHINES.
    const cards = (fixture.nativeElement as HTMLElement)
      .querySelectorAll('.poste:not(.poste--heberge)');
    expect(cards.length).toBe(1);
    expect(text()).toContain('Poste CAGIP');
    // F-97 / SF-97-02 : l'état DATE au lieu d'affirmer.
    expect(text()).toContain('en ligne · vu il y a');
  });

  // F-56 / SF-56-01 — les pastilles de statut viennent de la charte (DESIGN_SYSTEM.md §5). Elles
  // portaient jusque-là un préfixe `cg-` qui ne correspondait à aucune classe existante : elles
  // s'affichaient en texte nu, sans fond ni couleur.
  it('habille les statuts avec les pastilles de la charte', () => {
    setup([{ ...poste, elevated: true }]);
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('.badge.badge--success')).not.toBeNull();
    expect(root.querySelector('.badge.badge--warning')).not.toBeNull();
    expect(root.querySelectorAll('[class*="cg-badge"]').length).toBe(0);
  });

  // ---------------------------------------------------- appartenance (SF-49-03)

  it('donne à chaque poste sa pastille d\'initiales, dérivée de son nom', () => {
    setup();
    const mark = (fixture.nativeElement as HTMLElement)
      .querySelector('.host-badge__mark') as HTMLElement;

    expect(mark.textContent?.trim()).toBe(hostInitials('Poste CAGIP'));
    expect(mark.style.background).not.toBe('');
  });

  it('porte la couleur du poste sur sa pastille d\'en-tête, et aucune sur les tuiles', () => {
    setup();
    const root = fixture.nativeElement as HTMLElement;
    const projects = root.querySelectorAll<HTMLElement>('.projet');

    expect(headMark().style.background).toContain(hexToRgb(hostTone('Poste CAGIP').solid));
    expect(projects.length).toBe(2);
    // La grille ne montre que les projets du poste ouvert : l'identité est déjà dans l'en-tête et la
    // ligne ouverte de la colonne (§16) — aucune tuile ne la répète.
    projects.forEach((project) => expect(project.getAttribute('style')).toBeNull());
  });

  it('donne à deux postes de noms différents deux couleurs différentes', () => {
    setup([
      { ...poste, id: 'h1', name: 'Poste bureau' },
      { ...poste, id: 'h2', name: 'Poste maison' },
    ]);
    const first = headMark().style.background;

    openHost('h2');

    expect(first).not.toBe('');
    expect(headMark().style.background).not.toBe(first);
  });

  it('ÉCRIT le nom du poste à côté de sa couleur — elle ne porte jamais seule l\'information', () => {
    setup();
    const heading = (fixture.nativeElement as HTMLElement).querySelector('.poste h2');

    expect(heading?.textContent?.trim()).toBe('Poste CAGIP');
  });

  it('tire la même couleur que la fonction pure partagée', () => {
    setup();
    const expected = hostTone('Poste CAGIP').solid.toLowerCase();
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(expected.slice(i, i + 2), 16));

    expect(headMark().style.background).toContain(`${r}, ${g}, ${b}`);
  });

  it('marque le poste déconnecté avec la pastille neutre', () => {
    setup([{ ...poste, connected: false, activeProjects: 0 }]);
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('.badge.badge--neutral')).not.toBeNull();
  });

  it('montre ce que la machine a déclaré : racine, système, interpréteur, version du runner', () => {
    setup();
    expect(text()).toContain('dev');
    expect(text()).toContain('linux');
    expect(text()).toContain('posix');
    // F-81 / SF-81-03 : « son runner est-il à jour ? » a une réponse à l'écran, au lieu d'être
    // devinée. Rien de plus : aucun geste n'en dépend et aucun runner n'est refusé.
    expect(text()).toContain('Runner');
    expect(text()).toContain('0.0.1');
  });

  it("omet une ligne plutôt que d'écrire « inconnu » quand le runner n'a rien déclaré", () => {
    setup([{ ...poste, rootName: null, os: null, shell: null, runnerVersion: null }]);
    expect(text()).not.toContain('Racine');
    expect(text()).not.toContain('Système');
    expect(text()).not.toContain('Interpréteur');
    expect(text()).not.toContain('Runner');
  });

  it("signale les droits d'administrateur quand le runner tourne élevé", () => {
    setup([{ ...poste, elevated: true }]);
    expect(text()).toContain('Administrateur');
  });

  it("ne parle pas d'administrateur quand le runner ne l'est pas", () => {
    setup();
    expect(text()).not.toContain('Administrateur');
  });

  it('rend les projets du poste en tuiles, ceux qui travaillent d’abord', () => {
    setup();
    const projects = (fixture.nativeElement as HTMLElement).querySelectorAll('.projet');
    expect(projects.length).toBe(2);
    expect(projects[0].textContent).toContain('web');
    expect(projects[1].textContent).toContain('api');
  });

  it('dit ce qui tourne', () => {
    setup();
    expect(component.activityLabel(poste)).toBe('1 projet actif');
    expect(component.activityLabel({ ...poste, activeProjects: 3 })).toBe('3 projets actifs');
  });

  it("retombe sur la dernière activité quand rien ne tourne", () => {
    const idle: RunnerHostOverview = {
      ...poste,
      activeProjects: 0,
      lastActivityAt: new Date(Date.now() - 300_000).toISOString(),
    };
    setup([idle]);
    expect(component.activityLabel(idle)).toBe('Dernière activité il y a 5 min');
  });

  it("n'a rien à dire d'un poste qui n'a jamais rien fait", () => {
    const neuf = { ...poste, activeProjects: 0, lastActivityAt: null };
    setup([neuf]);
    expect(component.activityLabel(neuf)).toBeNull();
  });

  it("montre le dernier outil, jamais sa cible", () => {
    setup();
    expect(component.projectActivityLabel(poste.projects[0])).toContain('bash');
    expect(component.projectActivityLabel(poste.projects[1])).toBeNull();
  });

  it('affiche un message dédié pour un poste sans projet', () => {
    setup([{ ...poste, projects: [] }]);
    expect(text()).toContain('Aucun projet sous ce poste');
  });

  it("supporte une réponse sans liste de projets", () => {
    setup([{ ...poste, projects: undefined as never }]);
    expect(component.hosts()[0].projects).toEqual([]);
  });

  it('affiche un état vide explicite quand aucun poste n’est connecté', () => {
    setup([]);
    expect(component.isEmpty()).toBeTrue();
    expect(text()).toContain('Aucun poste connecté');
  });

  // ------------------------------------------------------------------ libellés

  it('calcule les durées à l’écran, en secondes puis minutes puis heures puis jours', () => {
    setup();
    const ago = (seconds: number) => new Date(Date.now() - seconds * 1000).toISOString();
    expect(component.elapsedLabel(ago(5))).toBe('il y a 5 s');
    expect(component.elapsedLabel(ago(120))).toBe('il y a 2 min');
    expect(component.elapsedLabel(ago(7200))).toBe('il y a 2 h');
    expect(component.elapsedLabel(ago(172_800))).toBe('il y a 2 j');
    expect(component.elapsedLabel(null)).toBeNull();
    expect(component.elapsedLabel(undefined)).toBeNull();
  });

  it("dit depuis quand une machine déconnectée n’a plus donné signe", () => {
    setup([{ ...poste, connected: false, lastSeenAt: new Date(Date.now() - 18 * 60_000).toISOString() }]);
    expect(component.hostStateLabel(component.realHosts()[0]))
      .toBe('hors ligne · vu il y a 18 min');
  });

  it('dit « jamais connecté » quand la machine n’a jamais battu', () => {
    const jamais = { ...poste, id: 'h9', connected: false, lastSeenAt: null };
    setup([jamais]);
    expect(component.hostStateLabel(jamais)).toBe('jamais connecté');
  });

  // ------------------------------------------------ F-97 / SF-97-02 : le statut dit vrai

  it('un refus reçu ailleurs fait passer la pastille hors ligne, sans relire la vue', () => {
    setup();
    expect(text()).toContain('en ligne · vu il y a');
    const calls = service.runnerHostsOverview.calls.count();

    // Le terminal vient de recevoir « poste hors ligne » pour ce poste.
    TestBed.inject(HostPresenceService).markOffline('h1', Date.now() + 1_000);
    fixture.detectChanges();

    const badge = (fixture.nativeElement as HTMLElement)
      .querySelector('.poste:not(.poste--heberge) .poste__presence.badge--neutral') as HTMLElement;
    expect(badge).not.toBeNull();
    expect(badge.textContent).toContain('Hors ligne');
    expect(text()).toContain('hors ligne · vu il y a');
    expect(service.runnerHostsOverview.calls.count()).toBe(calls);
  });

  it('le libellé daté avance avec l’horloge de l’écran, sans requête', () => {
    setup([{ ...poste, lastSeenAt: new Date(Date.now() - 10_000).toISOString() }]);
    const presence = TestBed.inject(HostPresenceService);
    const calls = service.runnerHostsOverview.calls.count();
    const before = component.hostStateLabel(component.realHosts()[0]);

    presence.now.set(presence.now() + 120_000);

    expect(before).toMatch(/^en ligne · vu il y a \d+ s$/);
    expect(component.hostStateLabel(component.realHosts()[0])).toMatch(/^en ligne · vu il y a 2 min$/);
    expect(service.runnerHostsOverview.calls.count()).toBe(calls);
  });

  // ------------------------------------------------------------------ navigation

  it('ouvre le terminal du projet en un clic', () => {
    setup();
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');
    component.openTerminal(poste.projects[0]);
    expect(navigate).toHaveBeenCalledWith(['/atelier', 'w1']);
  });

  // ------------------------------------------------------------------ rafraîchissement

  it('rejoue la lecture toutes les quinze secondes, et cesse à la destruction', fakeAsync(() => {
    setup();
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(1);

    tick(POSTES_REFRESH_MS);
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(2);

    fixture.destroy();
    tick(POSTES_REFRESH_MS * 2);
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(2);
  }));

  it("n'ouvre aucun flux : la vue est un sondage, pas un canal", () => {
    setup();
    // Le service n'expose qu'une lecture à cet écran ; aucune méthode de flux n'est appelée.
    expect(Object.keys(service).filter((key) => key.startsWith('stream'))).toEqual([]);
    expect(service.runnerHostsOverview).toHaveBeenCalled();
  });

  it('conserve la vue précédente quand un rafraîchissement échoue', fakeAsync(() => {
    setup();
    service.runnerHostsOverview.and.returnValue(throwError(() => new Error('réseau')));

    tick(POSTES_REFRESH_MS);

    expect(component.hosts().length).toBe(1);
    expect(component.error()).toBe('none');
    fixture.destroy();
  }));

  it('affiche l’erreur et un bouton Réessayer quand le premier chargement échoue', () => {
    service = jasmine.createSpyObj<AtelierService>('AtelierService',
      ['runnerHostsOverview', 'setHostMissionStatus', 'teamsAccess']);
    // F-89 / SF-89-03 : le droit Teams est lu au chargement, même quand la vue échoue.
    service.teamsAccess.and.returnValue(of({ entitled: false }));
    service.runnerHostsOverview.and.returnValue(throwError(() => new Error('réseau')));
    build();

    expect(component.error()).toBe('network');
    expect(text()).toContain('Réessayer');
  });

  it("dit que la vue appartient à la Forge quand l'accès est refusé", fakeAsync(() => {
    service = jasmine.createSpyObj<AtelierService>('AtelierService',
      ['runnerHostsOverview', 'setHostMissionStatus', 'teamsAccess']);
    service.teamsAccess.and.returnValue(of({ entitled: false }));
    service.runnerHostsOverview.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 403 })),
    );
    build();

    expect(component.error()).toBe('forbidden');
    expect(text()).toContain('La vue des postes fait partie de la Forge');

    // Un refus d'accès ne se répare pas en relisant : le sondage s'arrête.
    tick(POSTES_REFRESH_MS * 3);
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(1);
    fixture.destroy();
  }));

  it('date la dernière lecture réussie', () => {
    setup();
    expect(component.lastUpdatedLabel()).toMatch(/^\d{2}:\d{2}$/);
  });

  // ------------------------------------------- état de mission (F-60 / SF-60-02)

  /** Un poste dans l'état de mission demandé, sans projet — l'essentiel tient à l'en-tête. */
  function mission(id: string, name: string,
    missionStatus: RunnerHostOverview['missionStatus']): RunnerHostOverview {
    return { ...poste, id, name, missionStatus, projects: [] };
  }

  it('écrit toujours le libellé de l’état, jamais la couleur seule', () => {
    setup([mission('h1', 'Poste CAGIP', 'PENDING')]);
    expect(text()).toContain('En attente');
  });

  it('lit un poste sans état de mission comme une mission en cours', () => {
    // Backend antérieur, champ absent : jamais « inconnu », jamais une pastille vide.
    setup([{ ...poste, missionStatus: undefined, projects: [] }]);
    expect(component.mission(component.hosts()[0])).toBe('ACTIVE');
    expect(text()).toContain('En cours');
  });

  it("garde le filet d'identité du poste quel que soit son état de mission", () => {
    // LE PIÈGE DU CADRAGE : la couleur de mission ne doit pas remplacer, ni concurrencer, la
    // couleur d'identité. Deux postes de même état mais de noms différents gardent deux filets
    // différents ; le filet ne dépend que du nom.
    setup([mission('h1', 'Poste CAGIP', 'PENDING'), mission('h2', 'Poste Bercy', 'PENDING')]);
    expect(headMark().style.background).toContain(hexToRgb(hostTone('Poste CAGIP').solid));
    openHost('h2');
    expect(headMark().style.background).toContain(hexToRgb(hostTone('Poste Bercy').solid));
  });

  it("donne le même filet à un même nom, quels que soient les états de mission", () => {
    setup([mission('h1', 'Poste CAGIP', 'ACTIVE'), mission('h2', 'Poste CAGIP', 'CLOSED')]);
    const active = headMark().style.background;

    openHost('h2');

    expect(active).not.toBe('');
    expect(headMark().style.background).toBe(active);
  });

  it("n'emprunte aucune couleur d'identité pour la pastille d'état", () => {
    // La pastille de mission prend les classes de STATUT de la charte (§5) — rien n'est posé en
    // ligne, donc rien ne peut venir de la palette d'identité (§9).
    setup([mission('h1', 'Poste CAGIP', 'PENDING')]);
    const badge = (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLElement>('app-mission-badge .badge');

    expect(badge).not.toBeNull();
    expect(badge!.classList).toContain('badge--warning');
    expect(badge!.getAttribute('style')).toBeNull();
  });

  it('range un poste clôturé hors de la vue principale, sans le perdre', () => {
    setup([mission('h1', 'Poste CAGIP', 'ACTIVE'), mission('h2', 'Poste Bercy', 'CLOSED')]);

    expect(component.openHosts().map((h) => h.id)).toEqual(['h1']);
    expect(component.closedHosts().map((h) => h.id)).toEqual(['h2']);
    // Le repli annonce ce qu'il contient, et il est refermé au départ.
    expect(component.closedOpen()).toBeFalse();
    expect(text()).toContain('Missions clôturées (1)');
    expect(text()).not.toContain('Poste Bercy');

    component.toggleClosed();
    fixture.detectChanges();
    expect(text()).toContain('Poste Bercy');
  });

  it('garde le repli accessible quand toutes les missions sont clôturées', () => {
    setup([mission('h1', 'Poste CAGIP', 'CLOSED')]);

    expect(component.allClosed()).toBeTrue();
    expect(text()).toContain('Toutes vos missions sont clôturées');
    expect(text()).toContain('Missions clôturées (1)');
  });

  it("déclare l'état choisi et attend la réponse pour ranger la carte", () => {
    setup([mission('h1', 'Poste CAGIP', 'ACTIVE')]);
    service.setHostMissionStatus.and.returnValue(
      of({ id: 'h1', name: 'Poste CAGIP', connected: true, missionStatus: 'CLOSED',
        createdAt: new Date().toISOString() }),
    );

    component.setMission(component.hosts()[0], 'CLOSED');
    fixture.detectChanges();

    expect(service.setHostMissionStatus).toHaveBeenCalledWith('h1', 'CLOSED');
    expect(component.openHosts().length).toBe(0);
    expect(component.closedHosts().length).toBe(1);
  });

  it("suit la réponse de la gateway, pas la valeur demandée", () => {
    // C'est la réponse qui fait foi : elle seule sait ce qui a réellement été enregistré.
    setup([mission('h1', 'Poste CAGIP', 'ACTIVE')]);
    service.setHostMissionStatus.and.returnValue(
      of({ id: 'h1', name: 'Poste CAGIP', connected: true, missionStatus: 'PENDING',
        createdAt: new Date().toISOString() }),
    );

    component.setMission(component.hosts()[0], 'CLOSED');

    expect(component.mission(component.hosts()[0])).toBe('PENDING');
  });

  it("laisse l'état inchangé quand la gateway refuse", () => {
    setup([mission('h1', 'Poste CAGIP', 'ACTIVE')]);
    service.setHostMissionStatus.and.returnValue(throwError(() => new Error('réseau')));

    component.setMission(component.hosts()[0], 'CLOSED');
    fixture.detectChanges();

    expect(component.mission(component.hosts()[0])).toBe('ACTIVE');
    expect(component.openHosts().length).toBe(1);
    expect(component.savingHostId()).toBeNull();
  });

  it("n'appelle pas la gateway pour l'état déjà en place", () => {
    setup([mission('h1', 'Poste CAGIP', 'PENDING')]);

    component.setMission(component.hosts()[0], 'PENDING');

    expect(service.setHostMissionStatus).not.toHaveBeenCalled();
  });

  it('garde les projets et le terminal d’un poste clôturé', () => {
    // « Se ranger sans disparaître » : la carte rangée est EXACTEMENT la même carte.
    setup([{ ...poste, missionStatus: 'CLOSED' }]);
    openHost('h1');

    const buttons = (fixture.nativeElement as HTMLElement)
      .querySelectorAll('.projet button[aria-label^="Ouvrir le terminal"]');
    expect(buttons.length).toBe(2);
  });

  // ------------------------------------- accueil de la Forge (F-68 / SF-68-01)

  describe('la colonne et le poste ouvert (F-98 / SF-98-01)', () => {
    const bercy: RunnerHostOverview = { ...poste, id: 'h2', name: 'Poste Bercy', projects: [] };

    function railRows(): HTMLElement[] {
      return Array.from((fixture.nativeElement as HTMLElement)
        .querySelectorAll<HTMLElement>('.forge-rail__host'));
    }

    function groupLabels(): string[] {
      return Array.from((fixture.nativeElement as HTMLElement)
        .querySelectorAll('.forge-rail__group')).map((node) => node.textContent?.trim() ?? '');
    }

    it('n\'a qu\'une porte « Voir travailler », vers /forge/voir (F-98 / SF-98-04)', () => {
      setup();
      const fleet = (fixture.nativeElement as HTMLElement).querySelector('.forge-fleet') as HTMLElement;
      const watch = fleet.querySelector('.forge-fleet__watch') as HTMLAnchorElement;

      expect(watch.getAttribute('href')).toBe('/forge/voir');
      expect(watch.textContent).toContain('Voir travailler');
      expect(fleet.textContent).not.toContain('Mosaïque');
      expect(fleet.querySelector('a[href="/forge/supervision"], a[href="/forge/mosaique"]')).toBeNull();
    });

    it('se nomme « Forge » en tête du bandeau : cet écran est la racine de la Forge', () => {
      setup();
      const title = (fixture.nativeElement as HTMLElement).querySelector('.forge-fleet__title');

      expect(title?.textContent?.trim()).toBe('Forge');
    });

    it('liste chaque poste dans la colonne, et n\'ouvre QU\'UN poste à droite', () => {
      setup([poste, bercy]);

      expect(railRows().map((row) => row.getAttribute('data-ref'))).toEqual(['h1', 'h2', 'heberge']);
      expect((fixture.nativeElement as HTMLElement)
        .querySelectorAll('.poste:not(.poste--heberge)').length).toBe(1);
    });

    it('ouvre le poste désigné par l\'URL, et suit son changement sans rien relire', () => {
      setup([poste, bercy]);
      expect(component.selectedHost().id).toBe('h1');

      openHost('h2');

      expect(component.selectedHost().id).toBe('h2');
      expect((fixture.nativeElement as HTMLElement).querySelector('.poste h2')?.textContent?.trim())
        .toBe('Poste Bercy');
      // Aucune relecture : changer de poste ne recharge rien.
      expect(service.runnerHostsOverview).toHaveBeenCalledTimes(1);
    });

    it('un poste inconnu dans l\'URL retombe sur le poste par défaut, sans erreur', () => {
      setup([poste, bercy]);

      openHost('supprime');

      expect(component.selectedRef()).toBe('h1');
    });

    it('/forge ouvre d\'abord le poste où une autorisation attend', () => {
      const attend: RunnerHostOverview = {
        ...bercy,
        projects: [{
          ...poste.projects[0], id: 'w5', liveTerminal: true,
          terminalPreview: { activity: 'AWAITING_APPROVAL', activityDetail: 'aws s3 ls', lines: [] },
        }],
      };
      setup([poste, attend]);

      expect(component.selectedRef()).toBe('h2');
      expect(groupLabels()[0]).toBe('À regarder');
      expect(railRows()[0].textContent).toContain('1 attend');
    });

    it('/forge ouvre le premier poste en ligne avant un poste hors ligne', () => {
      setup([{ ...poste, id: 'h0', name: 'Éteint', connected: false }, bercy]);

      expect(component.selectedRef()).toBe('h2');
      expect(groupLabels()).toEqual(['En ligne', 'Hors ligne', 'Sans machine']);
    });

    it('cliquer une ligne mène à /forge/<id>, en gardant les paramètres de requête', () => {
      setup([poste, bercy]);
      const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

      railRows()[1].click();

      expect(navigate).toHaveBeenCalledWith(['/forge', 'h2'], { queryParamsHandling: 'preserve' });
    });

    it('l\'ancien ancrage #poste-<id> redirige vers /forge/<id>, sans laisser le fragment derrière', () => {
      service = spyService();
      service.runnerHostsOverview.and.returnValue(of([poste]));
      const navigate = spyOn(Router.prototype, 'navigate').and.resolveTo(true);

      build('poste-h1');

      expect(navigate).toHaveBeenCalledWith(['/forge', 'h1'], { replaceUrl: true });
    });

    it('ignore un fragment qui ne désigne pas un poste', () => {
      service = spyService();
      service.runnerHostsOverview.and.returnValue(of([poste]));
      const navigate = spyOn(Router.prototype, 'navigate').and.resolveTo(true);

      build('autre-chose');

      expect(navigate).not.toHaveBeenCalled();
    });

    it('le filtre trouve un poste par le nom d\'un de ses projets, et dit combien', () => {
      setup([poste, bercy]);

      component.filter.set('api');
      fixture.detectChanges();

      expect(railRows().map((row) => row.getAttribute('data-ref'))).toEqual(['h1']);
      expect(railRows()[0].textContent).toContain('1 projet trouvé');
    });

    it('dit quand rien ne correspond, sans fermer le poste ouvert', () => {
      setup([poste, bercy]);

      component.filter.set('zzz');
      fixture.detectChanges();

      expect(text()).toContain('Aucun poste ni projet ne correspond.');
      expect(component.selectedHost().id).toBe('h1');
    });

    it('le bandeau compte les postes en ligne et les autorisations qui attendent, sans clic', () => {
      const attend: RunnerHostOverview = {
        ...bercy,
        connected: false,
        hostTerminalPreview: { activity: 'AWAITING_APPROVAL', activityDetail: 'git clone', lines: [] },
      };
      setup([poste, attend]);
      const fleet = (fixture.nativeElement as HTMLElement).querySelector('.forge-fleet') as HTMLElement;

      expect(fleet.textContent).toContain('1 poste en ligne sur 2');
      expect(fleet.textContent).toContain('1 autorisation attend');
    });

    it('ouvre d\'office le repli quand le poste ouvert est clôturé', () => {
      setup([poste, { ...bercy, missionStatus: 'CLOSED' }]);
      expect(component.closedOpen()).toBeFalse();

      openHost('h2');
      component.refresh();
      fixture.detectChanges();

      expect(component.closedOpen()).toBeTrue();
      expect(railRows().some((row) => row.getAttribute('data-ref') === 'h2')).toBeTrue();
    });
  });

  describe('le poste ouvert et ses onglets (F-98 / SF-98-02)', () => {
    function tabs(): HTMLElement[] {
      return Array.from((fixture.nativeElement as HTMLElement)
        .querySelectorAll<HTMLElement>('.poste__tab'));
    }

    function tab(name: string): HTMLElement {
      return tabs().find((node) => node.getAttribute('data-tab') === name) as HTMLElement;
    }

    it('dit dans l\'en-tête qui, dans quel état, et ce que la machine a déclaré', () => {
      setup();
      const head = (fixture.nativeElement as HTMLElement).querySelector('.poste__head') as HTMLElement;

      expect(head.querySelector('h2')?.textContent?.trim()).toBe('Poste CAGIP');
      expect(head.querySelector('.poste__presence')?.textContent).toContain('En ligne');
      expect(head.textContent).toContain('vu il y a');
      expect(head.textContent).toContain('dev');
      expect(head.textContent).toContain('linux');
      expect(head.textContent).toContain('posix');
      expect(head.textContent).toContain('Runner');
      // Les gestes du poste vivent dans l'en-tête : plus de bloc « terminal » dédié.
      expect(head.querySelector('.poste__host-terminal')).not.toBeNull();
      expect(head.querySelector('.poste__menu-trigger')).not.toBeNull();
    });

    it('dit « Jamais connecté » sans inventer de date', () => {
      setup([{ ...poste, id: 'h9', connected: false, lastSeenAt: null }]);
      const head = (fixture.nativeElement as HTMLElement).querySelector('.poste__head') as HTMLElement;

      expect(head.querySelector('.poste__presence')?.textContent).toContain('Jamais connecté');
      expect(head.textContent).not.toContain('vu il y a');
    });

    it('propose quatre onglets à une machine, et Projets seul à « Hébergé »', () => {
      setup();
      expect(tabs().map((node) => node.getAttribute('data-tab')))
        .toEqual(['projets', 'carte', 'gouvernance', 'activite']);

      openHost('heberge');
      expect(tabs().map((node) => node.getAttribute('data-tab'))).toEqual(['projets']);
    });

    it('chaque onglet porte son résumé sans être ouvert', () => {
      setup();

      expect(tab('projets').textContent).toContain('2');
      expect(tab('carte').textContent).toContain('3 faits');
      expect(tab('gouvernance').querySelector('.badge')).toBeNull();
      expect(tab('projets').getAttribute('aria-selected')).toBe('true');
    });

    it('l\'onglet Carte dit « hors ligne » quand le poste l\'est', () => {
      setup([{ ...poste, connected: false }]);

      expect(tab('carte').textContent).toContain('hors ligne');
    });

    it('« à appliquer » quand une mise à jour de paquet attend, et le bandeau dans Gouvernance', () => {
      service = spyService();
      service.runnerHostsOverview.and.returnValue(of([poste]));
      build();
      governance.getHosts.and.returnValue(of([
        { ref: 'h1', id: 'h1', name: 'Poste CAGIP', virtual: false, projects: 2, active: 1, outdated: 2 },
      ]));
      component.refresh();
      fixture.detectChanges();

      const flag = tab('gouvernance').querySelector('.badge') as HTMLElement;
      expect(flag.textContent?.trim()).toBe('à appliquer');
      expect(flag.classList).toContain('badge--warning');

      openTab('gouvernance');
      expect(text()).toContain('Une version plus récente de 2 paquet(s) existe.');
      expect(text()).toContain('Rien n\'a été écrit sur cette machine');
    });

    it('« à corriger » quand l\'intégrité relève une erreur', () => {
      setup();
      governance.getIntegrite.and.returnValue(of({
        ...integriteSaine,
        errors: [{ rule: 'carte/fichier-absent', target: 'reseau.md', message: 'manque' }],
      }));
      component.refresh();
      fixture.detectChanges();

      const flag = tab('gouvernance').querySelector('.badge') as HTMLElement;
      expect(flag.textContent?.trim()).toBe('à corriger');
      expect(flag.classList).toContain('badge--error');
    });

    it('lit les mises à jour une fois, les relit sur « Rafraîchir », jamais au sondage', fakeAsync(() => {
      setup();
      expect(governance.getHosts).toHaveBeenCalledTimes(1);

      tick(POSTES_REFRESH_MS);
      expect(governance.getHosts).toHaveBeenCalledTimes(1);

      component.refresh();
      expect(governance.getHosts).toHaveBeenCalledTimes(2);
      fixture.destroy();
    }));

    it('une lecture des mises à jour en échec reste silencieuse', () => {
      setup();
      governance.getHosts.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      component.refresh();
      fixture.detectChanges();

      expect(tab('gouvernance').querySelector('.badge')).toBeNull();
      expect(component.outdatedOn(poste)).toBe(0);
    });

    it('?onglet=carte ouvre la carte ; un onglet inconnu ouvre Projets', () => {
      setup();

      openTab('carte');
      expect(component.activeTab()).toBe('carte');
      expect(text()).toContain('Carte du poste');
      expect(text()).not.toContain('Ajouter un projet');

      openTab('radar');
      expect(component.activeTab()).toBe('projets');
      expect(text()).toContain('Ajouter un projet');
    });

    it('« Hébergé » retombe sur Projets quel que soit l\'onglet demandé', () => {
      setup();
      openTab('carte');

      openHost('heberge');

      expect(component.activeTab()).toBe('projets');
    });

    it('cliquer un onglet le met dans l\'URL, et Projets l\'en retire', () => {
      setup();
      const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

      tab('gouvernance').click();
      expect(navigate).toHaveBeenCalledWith([], jasmine.objectContaining({
        queryParams: { onglet: 'gouvernance' }, queryParamsHandling: 'merge',
      }));

      tab('projets').click();
      expect(navigate).toHaveBeenCalledWith([], jasmine.objectContaining({
        queryParams: { onglet: null },
      }));
    });

    it('l\'onglet Activité dit ce qui tourne et les projets par activité récente', () => {
      setup();
      openTab('activite');

      expect(text()).toContain('1 projet actif');
      const items = Array.from((fixture.nativeElement as HTMLElement)
        .querySelectorAll('.poste__activity-list li')).map((node) => node.textContent ?? '');
      expect(items.length).toBe(1);
      expect(items[0]).toContain('web');
      expect(items[0]).toContain('bash');
    });

    it('« Hébergé » garde ses deux gestes sans machine dans son en-tête', () => {
      setup();
      openHost('heberge');
      const head = (fixture.nativeElement as HTMLElement).querySelector('.poste__head') as HTMLElement;

      expect(head.textContent).toContain('Ouvrir un dépôt GitHub');
      expect(head.textContent).toContain('Importer une archive .zip');
      expect(head.textContent).toContain('chez la gateway');
    });
  });

  describe('les projets en grille (F-98 / SF-98-03)', () => {
    const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

    const flotte: RunnerHostOverview = {
      ...poste,
      projects: [
        { ...poste.projects[1], id: 'a', name: 'zeta', lastActivityAt: minutesAgo(1), active: false },
        { ...poste.projects[1], id: 'b', name: 'Alpha', lastActivityAt: minutesAgo(30), active: false },
        {
          ...poste.projects[1], id: 'c', name: 'migration', lastActivityAt: minutesAgo(60),
          liveTerminal: true,
          terminalPreview: { activity: 'AWAITING_APPROVAL', activityDetail: 'aws s3 ls', lines: [] },
        },
        { ...poste.projects[1], id: 'd', name: 'beta', lastActivityAt: null, active: false },
      ],
    };

    function names(): string[] {
      return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.projet__name'))
        .map((node) => node.textContent?.trim() ?? '');
    }

    function sortBy(key: string): void {
      ((fixture.nativeElement as HTMLElement)
        .querySelector(`[data-sort="${key}"]`) as HTMLButtonElement).click();
      fixture.detectChanges();
    }

    it('range les tuiles dans une grille, ce qui attend en tête par défaut', () => {
      setup([flotte]);
      const grid = (fixture.nativeElement as HTMLElement).querySelector('.poste__grid') as HTMLElement;

      expect(grid.querySelectorAll('app-forge-project-tile').length).toBe(4);
      expect(component.projectSort()).toBe('actifs');
      expect(names()).toEqual(['migration', 'zeta', 'Alpha', 'beta']);
      const first = grid.querySelector('.projet') as HTMLElement;
      expect(first.classList).toContain('projet--awaiting');
      expect(first.textContent).toContain('Attend votre autorisation');
      expect(first.textContent).toContain('aws s3 ls');
    });

    it('trie A → Z, puis par activité récente', () => {
      setup([flotte]);

      sortBy('alpha');
      expect(names()).toEqual(['Alpha', 'beta', 'migration', 'zeta']);

      sortBy('recents');
      expect(names()).toEqual(['zeta', 'Alpha', 'migration', 'beta']);
    });

    it('une tuile sans aperçu dit depuis quand elle est au repos', () => {
      setup([flotte]);
      const tiles = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.projet'));

      expect(tiles[2].textContent).toContain('Au repos · dernier tour il y a 30 min');
      expect(tiles[3].textContent).toContain('Au repos · aucun tour');
    });

    it('« Ouvrir » entre dans le terminal du projet', () => {
      setup([flotte]);
      const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

      ((fixture.nativeElement as HTMLElement).querySelector('.projet__open') as HTMLButtonElement).click();

      expect(navigate).toHaveBeenCalledWith(['/atelier', 'c']);
    });

    it('le filtre de la colonne ne garde dans la grille que les projets qui correspondent', () => {
      setup([flotte]);

      component.filter.set('ALPH');
      fixture.detectChanges();

      expect(names()).toEqual(['Alpha']);
      expect(text()).toContain('1 projet correspond au filtre');
    });

    it('LE CRITÈRE DE LA FEATURE : 4 postes, 15 projets, 1440 × 900 — la colonne et 12 tuiles sans défiler', () => {
      // Les dimensions de la fenêtre de test sont fixées par karma.conf.js (--window-size=1440,900).
      // (moins la barre de défilement de la page de test, comme sur un vrai portable)
      expect(window.innerWidth).withContext('fenêtre de test attendue en 1440 px').toBeGreaterThanOrEqual(1400);
      const quinze: RunnerHostOverview = {
        ...poste,
        id: 'h1', name: 'EDENRED', activeProjects: 0,
        projects: Array.from({ length: 15 }, (_, i) => ({
          ...poste.projects[1], id: `p${i}`, name: `projet-${i}`, projectPath: `projet-${i}`,
          lastActivityAt: minutesAgo(10 + i),
        })),
      };
      setup([
        quinze,
        { ...poste, id: 'h2', name: 'FREE' },
        { ...poste, id: 'h3', name: 'CAGIP', connected: false },
        { ...poste, id: 'h4', name: 'Richemont', connected: false, lastSeenAt: null, projects: [] },
      ]);
      const root = fixture.nativeElement as HTMLElement;
      root.style.display = 'block';
      root.style.width = `${window.innerWidth}px`;
      fixture.detectChanges();

      // La barre de l'application (64 px, §4) est au-dessus de la Forge dans la vraie page.
      const pageTop = root.getBoundingClientRect().top - 64;
      const bottomOf = (node: Element) => node.getBoundingClientRect().bottom - pageTop;
      const tiles = Array.from(root.querySelectorAll('app-forge-project-tile'));
      const rows = Array.from(root.querySelectorAll('.forge-rail__host'));

      expect(tiles.length).toBe(15);
      expect(rows.length).toBe(5);
      expect(bottomOf(tiles[11])).withContext('la 12e tuile doit tenir dans 900 px').toBeLessThanOrEqual(900);
      expect(bottomOf(rows[rows.length - 1])).withContext('toute la colonne doit tenir').toBeLessThanOrEqual(900);
    });

    it('un filtre qui retient le poste par son nom garde toute la grille', () => {
      setup([flotte]);

      component.filter.set('cagip');
      fixture.detectChanges();

      expect(names().length).toBe(4);
      expect(text()).not.toContain('au filtre');
    });
  });

  /**
   * Le signe de vie sur la carte du poste (F-70 / SF-70-02). Le PO a tranché **le même** signe qu'au
   * terminal : une pastille et le mot écrit. Ce qui se vérifie ici, c'est qu'il soit lisible **et**
   * qu'il ne se confonde pas avec le « Connecté » du runner, deux pastilles plus haut.
   */
  describe('terminaux vivants (F-70)', () => {
    it('montre la pastille et le mot sur la carte dont un projet vit', () => {
      setup([{ ...poste, liveTerminals: 1 }]);
      const dom = fixture.nativeElement as HTMLElement;
      const badge = dom.querySelector('app-live-badge');
      expect(badge).not.toBeNull();
      expect(badge?.textContent?.trim()).toBe('Terminal connecté');
    });

    it('accorde au pluriel quand deux terminaux vivent sur le même poste', () => {
      setup([{ ...poste, liveTerminals: 2 }]);
      const dom = fixture.nativeElement as HTMLElement;
      expect(dom.textContent).toContain('2 terminaux connectés');
    });

    it('n\'affiche aucune pastille de vie quand rien ne vit', () => {
      setup([{ ...poste, liveTerminals: 0 }]);
      const dom = fixture.nativeElement as HTMLElement;
      expect(dom.querySelector('app-live-badge')).toBeNull();
    });

    it('marque la LIGNE du projet dont le terminal est ouvert, pas les autres', () => {
      setup([
        {
          ...poste,
          liveTerminals: 1,
          projects: [
            { ...poste.projects[0], liveTerminal: true },
            { ...poste.projects[1], liveTerminal: false },
          ],
        },
      ]);
      const dom = fixture.nativeElement as HTMLElement;
      const lines = Array.from(dom.querySelectorAll('.projet'));
      expect(lines.length).toBe(2);
      expect(lines[0].querySelector('app-live-badge')).not.toBeNull();
      expect(lines[1].querySelector('app-live-badge')).toBeNull();
    });

    it('dit en tête ce que quatre flux engagent — un garde-fou qu\'on ne découvre pas en le heurtant', () => {
      setup([{ ...poste, liveTerminals: 2 }]);
      const dom = fixture.nativeElement as HTMLElement;
      const counter = dom.querySelector('.postes__live');
      expect(counter?.textContent).toContain('2\u00a0/ 4 terminaux vivants');
      // La phrase est écrite à l'écran, pas rangée dans une infobulle.
      expect(counter?.textContent).toContain('consomme un tour en parallèle');
    });

    it('compte les terminaux vivants de TOUS les postes', () => {
      setup([
        { ...poste, liveTerminals: 1 },
        { ...poste, id: 'h2', name: 'Poste BNP', liveTerminals: 2 },
      ]);
      expect(component.liveTerminalCount()).toBe(3);
    });
  });
  /**
   * L'aperçu vivant sur la carte du poste (F-76 / SF-76-02) — la **première densité**. Ce qui s'y
   * vérifie : qu'on voie **qu'un agent attend quelque chose sans rien ouvrir**, et que rien ne
   * s'affiche quand il n'y a rien à dire.
   */
  describe('aperçu des terminaux (F-76)', () => {
    it('montre ce que fait le terminal, sous le nom du projet', () => {
      setup([
        {
          ...poste,
          liveTerminals: 1,
          projects: [
            {
              ...poste.projects[0],
              liveTerminal: true,
              terminalPreview: {
                activity: 'RUNNING',
                activityDetail: 'npm test',
                lines: ['$ npm test', 'PASS src/app.spec.ts'],
              },
            },
            { ...poste.projects[1], liveTerminal: false },
          ],
        },
      ]);
      const lines = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.projet'));
      expect(lines[0].textContent).toContain('Exécute npm test');
      expect(lines[0].textContent).toContain('PASS src/app.spec.ts');
      expect(lines[1].querySelector('.preview')).toBeNull();
    });

    it('signale FRANCHEMENT le projet qui attend une autorisation', () => {
      // C'est exactement ce qui a échappé à l'utilisateur pendant douze heures le 2026-09-08.
      setup([
        {
          ...poste,
          liveTerminals: 1,
          projects: [
            {
              ...poste.projects[0],
              liveTerminal: true,
              terminalPreview: {
                activity: 'AWAITING_APPROVAL',
                activityDetail: 'rm -rf build',
                lines: ['Autorisation demandée'],
              },
            },
            poste.projects[1],
          ],
        },
      ]);
      const dom = fixture.nativeElement as HTMLElement;
      // Le libellé est ÉCRIT : la couleur ne le porte jamais seule.
      expect(dom.textContent).toContain('Attend votre autorisation');
      expect(dom.querySelector('.preview--awaiting')).not.toBeNull();
    });

    it("n'affiche aucun bloc quand la gateway ne rend pas d'aperçu", () => {
      // Un backend antérieur à F-76, ou un terminal qui vient de s'ouvrir : la carte reste
      // exactement ce qu'elle était.
      setup([{ ...poste, liveTerminals: 1, projects: [{ ...poste.projects[0], liveTerminal: true }] }]);
      const dom = fixture.nativeElement as HTMLElement;
      expect(dom.querySelector('.preview')).toBeNull();
      // Non-régression F-70 : la pastille de vie et le compteur restent là.
      expect(dom.querySelector('app-live-badge')).not.toBeNull();
      expect(dom.querySelector('.postes__live')?.textContent).toContain('1\u00a0/ 4 terminaux vivants');
    });

    it('montre aussi ce que fait le terminal DU POSTE (F-74)', () => {
      setup([
        {
          ...poste,
          hostTerminalId: 'ht-1',
          hostTerminalLive: true,
          hostTerminalPreview: {
            activity: 'RUNNING',
            activityDetail: 'git clone',
            lines: ['$ git clone', 'Cloning...'],
          },
        },
      ]);
      openTab('activite');
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Exécute git clone');
    });
  });

  // ------------------------------------------- suppression d'un poste (F-69 / SF-69-02)

  it('propose « Supprimer le poste » dans le menu de la carte', () => {
    setup();
    const trigger = (fixture.nativeElement as HTMLElement)
      .querySelector('.poste__menu-trigger') as HTMLButtonElement;

    expect(trigger).not.toBeNull();
    trigger.click();
    fixture.detectChanges();

    expect(document.body.textContent).toContain('Supprimer le poste');
  });

  it('ouvre le dialogue en lui passant le nombre de projets restants', () => {
    setup();

    component.deleteHost(component.hosts()[0]);

    expect(dialog.open).toHaveBeenCalled();
    const data = dialog.open.calls.mostRecent().args[1]?.data as {
      hostName: string;
      remainingProjects: number;
    };
    expect(data.hostName).toBe('Poste CAGIP');
    expect(data.remainingProjects).toBe(2);
  });

  it("n'appelle rien quand le dialogue est refermé sans confirmer", () => {
    setup();

    component.deleteHost(component.hosts()[0]);

    expect(service.deleteRunnerHost).not.toHaveBeenCalled();
    expect(component.hosts().length).toBe(1);
  });

  it('supprime le poste confirmé et retire sa carte', () => {
    dialogAnswer = true;
    setup([{ ...poste, projects: [] }]);
    service.deleteRunnerHost.and.returnValue(of(void 0));

    component.deleteHost(component.hosts()[0]);
    fixture.detectChanges();

    expect(service.deleteRunnerHost).toHaveBeenCalledOnceWith('h1');
    expect(component.hosts()).toEqual([]);
  });

  it('garde la carte quand la gateway refuse, et relit la vue', () => {
    // Le compte exact vient du serveur : l'écran pouvait être en retard d'un projet créé ailleurs.
    dialogAnswer = true;
    setup([{ ...poste, projects: [] }]);
    service.deleteRunnerHost.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409,
      error: { error: 'host_has_projects', message: '« Poste CAGIP » porte encore 3 projets.' },
    })));

    component.deleteHost(component.hosts()[0]);
    fixture.detectChanges();

    // La carte reste : rien n'a été supprimé, l'écran ne doit pas prétendre le contraire.
    expect(component.hosts().length).toBe(1);
    // Et la vue est relue — deux lectures : celle de l'ouverture, celle d'après l'échec.
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(2);
  });

  it('garde la carte quand le réseau ne répond pas', () => {
    dialogAnswer = true;
    setup([{ ...poste, projects: [] }]);
    service.deleteRunnerHost.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    component.deleteHost(component.hosts()[0]);
    fixture.detectChanges();

    expect(component.hosts().length).toBe(1);
    expect(component.deletingHostId()).toBeNull();
  });

  // ------------------------------------- le coupe-circuit (F-82 / SF-82-02)

  // Son bouton ne vivait que dans l'en-tête d'un terminal. Or un poste connecté SANS AUCUN PROJET
  // n'a pas de terminal, donc pas de bouton : la machine était branchée et on ne pouvait rien en
  // faire depuis l'application. C'est la situation vécue le 2026-09-12.

  it('propose « Couper la liaison » dans le menu de la carte, sous le même menu que la suppression',
    () => {
      setup();
      const trigger = (fixture.nativeElement as HTMLElement)
        .querySelector('.poste__menu-trigger') as HTMLButtonElement;

      trigger.click();
      fixture.detectChanges();

      expect(document.body.textContent).toContain('Couper la liaison');
      expect(document.body.textContent).toContain('Supprimer le poste');
      // Jamais en accès direct : le geste n'existe que DANS le menu de dépassement.
      const direct = Array.from((fixture.nativeElement as HTMLElement)
        .querySelectorAll('.poste__actions button:not(.poste__menu-trigger)'))
        .map((button) => button.textContent ?? '');
      expect(direct.some((label) => label.includes('Couper') || label.includes('Supprimer'))).toBeFalse();
    });

  it('coupe la liaison depuis la carte d\'un poste SANS AUCUN PROJET — le cas vécu', () => {
    dialogAnswer = true;
    setup([{ ...poste, projects: [], activeProjects: 0 }]);

    component.killHost(component.hosts()[0]);
    fixture.detectChanges();

    expect(service.killHost).toHaveBeenCalledOnceWith('h1');
    // Et la vue est relue : c'est la gateway qui fait foi sur ce qu'elle a coupé.
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(2);
  });

  it('passe au dialogue les projets NOMMÉS, pas leur nombre', () => {
    setup();

    component.killHost(component.hosts()[0]);

    const data = dialog.open.calls.mostRecent().args[1]?.data as {
      hostName: string;
      projects: { name: string }[];
    };
    expect(data.hostName).toBe('Poste CAGIP');
    expect(data.projects.map((p) => p.name)).toEqual(['web', 'api']);
  });

  it('n\'appelle rien quand le dialogue est refermé sans confirmer', () => {
    setup();

    component.killHost(component.hosts()[0]);

    expect(service.killHost).not.toHaveBeenCalled();
  });

  it('ne coupe rien sur le poste « Hébergé » : ce n\'est pas une machine', () => {
    dialogAnswer = true;
    setup([heberge]);

    component.killHost(heberge);

    expect(dialog.open).not.toHaveBeenCalled();
    expect(service.killHost).not.toHaveBeenCalled();
  });

  it('ignore un second clic pendant l\'aller-retour', () => {
    dialogAnswer = true;
    setup([{ ...poste, projects: [] }]);
    // Une réponse qui n'arrive jamais : le verrou doit tenir pendant tout l'aller-retour.
    service.killHost.and.returnValue(new Observable(() => undefined));

    component.killHost(component.hosts()[0]);
    component.killHost(component.hosts()[0]);

    expect(service.killHost).toHaveBeenCalledTimes(1);
    expect(component.killingHostId()).toBe('h1');
  });

  it('relâche le verrou et relit la vue quand la gateway refuse', () => {
    dialogAnswer = true;
    setup([{ ...poste, projects: [] }]);
    service.killHost.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

    component.killHost(component.hosts()[0]);
    fixture.detectChanges();

    expect(component.killingHostId()).toBeNull();
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(2);
  });

  it('ne change rien à ce que fait le coupe-circuit : un seul appel, celui qui existait déjà', () => {
    dialogAnswer = true;
    setup([{ ...poste, projects: [] }]);

    component.killHost(component.hosts()[0]);

    expect(service.killHost).toHaveBeenCalledOnceWith('h1');
    expect(service.deleteRunnerHost).not.toHaveBeenCalled();
    expect(service.setHostMissionStatus).not.toHaveBeenCalled();
  });


  // ------------------------------------- le poste « Hébergé » (F-71 / SF-71-03)

  /** Le poste virtuel tel que la gateway le rend : identifiant NUL, aucune machine derrière. */
  const heberge: RunnerHostOverview = {
    id: null,
    name: 'Hébergé',
    virtual: true,
    rootName: null,
    os: null,
    shell: null,
    elevated: null,
    connected: false,
    missionStatus: null,
    lastSeenAt: null,
    createdAt: null,
    lastActivityAt: null,
    activeProjects: 0,
    liveTerminals: 1,
    projects: [
      {
        id: 'w9',
        name: 'mon-depot',
        projectPath: null,
        executionTarget: 'SANDBOX',
        lastActivityAt: null,
        lastTool: null,
        calls: 0,
        active: false,
        liveTerminal: true,
      },
    ],
  };

  it('range les projets sans machine sous « Hébergé », après les machines', () => {
    setup([poste, heberge]);
    const rows = Array.from((fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLElement>('.forge-rail__host'));

    // Après les machines, dans son propre groupe — et sans point de statut : il n'a pas de runner.
    expect(rows.map((row) => row.getAttribute('data-ref'))).toEqual(['h1', 'heberge']);
    expect(rows[1].textContent).toContain('Hébergé');
    expect(rows[1].querySelector('.forge-rail__dot')).toBeNull();

    openHost('heberge');
    const card = (fixture.nativeElement as HTMLElement).querySelector('.poste') as HTMLElement;
    expect(card.textContent).toContain('mon-depot');
    expect(card.classList).toContain('poste--heberge');
  });

  it('ne lui donne ni identité de machine, ni état de connexion, ni mission, ni suppression', () => {
    setup([heberge]);
    const card = (fixture.nativeElement as HTMLElement).querySelector('.poste') as HTMLElement;

    // Le §9 réserve ses dix tons à l'identification d'une MACHINE : le poste virtuel n'en prend
    // aucun, et ne porte donc pas la pastille d'initiales.
    expect(component.tone(heberge)).toBeNull();
    expect(card.querySelector('.host-badge__mark')).toBeNull();
    // Ni « en ligne », ni « jamais connecté » : il n'a pas de runner.
    expect(card.textContent).not.toContain('en ligne');
    expect(card.textContent).not.toContain('jamais connecté');
    // Ni mission, ni menu de suppression.
    expect(card.querySelector('.poste__mission')).toBeNull();
    expect(card.querySelector('.poste__menu-trigger')).toBeNull();
    // Et il dit ce qu'il est.
    expect(card.textContent).toContain('chez la gateway');
  });

  it('compte ses terminaux vivants dans le total de l\'accueil', () => {
    // Jusqu'ici, un terminal ouvert sur un projet hébergé n'était compté nulle part.
    setup([{ ...poste, liveTerminals: 2 }, heberge]);

    expect(component.liveTerminalCount()).toBe(3);
  });

  it('refuse de supprimer ou de clôturer un poste qui n\'existe pas en base', () => {
    dialogAnswer = true;
    setup([heberge]);

    component.deleteHost(heberge);
    component.setMission(heberge, 'CLOSED');

    expect(dialog.open).not.toHaveBeenCalled();
    expect(service.deleteRunnerHost).not.toHaveBeenCalled();
    expect(service.setHostMissionStatus).not.toHaveBeenCalled();
  });

  it('donne à la carte virtuelle l\'adresse /forge/heberge : elle n\'a pas d\'identifiant', () => {
    setup([poste, heberge]);

    openHost('heberge');

    expect(component.selectedRef()).toBe('heberge');
    expect(component.selectedHost().virtual).toBeTrue();
  });

  // -------------------------------------------- « Connecter un poste » (F-72 / SF-72-02)

  it('offre « Connecter un poste » en tête d\'écran', () => {
    setup();

    expect(text()).toContain('Connecter un poste');
  });

  it('propose le même geste quand aucun poste n\'existe encore', () => {
    setup([]);

    // L'écran vide DIT le nouvel ordre : la machine d'abord, les projets ensuite.
    expect(text()).toContain('Connecter un poste');
    expect(text()).toContain('ensuite');
  });

  it('ouvre le parcours en mode POSTE : aucun projet ne lui est passé', () => {
    setup();

    component.connectHost();

    expect(dialog.open).toHaveBeenCalledTimes(1);
    const config = dialog.open.calls.mostRecent().args[1] as { data?: Record<string, unknown> };
    // C'est l'ABSENCE de projet qui met le dialogue en mode poste.
    expect(config.data).toEqual({});
  });

  it('relit la vue à la fermeture du parcours', () => {
    setup();
    service.runnerHostsOverview.calls.reset();

    component.connectHost();

    // Le poste existe peut-être maintenant, connecté ou non : la vue doit le montrer.
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(1);
  });

  // ------------------ « Ajouter un projet » et les dossiers non ouverts (F-72 / SF-72-03)

  it('offre « Ajouter un projet » sur la carte d\'une machine', () => {
    setup();

    expect(text()).toContain('Ajouter un projet');
  });

  it('compte dans la tuile fantôme les dossiers de la racine QUI NE SONT PAS encore ouverts', () => {
    setup();
    const ghost = (fixture.nativeElement as HTMLElement).querySelector('.poste__ghost') as HTMLElement;

    // `web` est déjà pris par un projet (`used`) : il ne compte pas — seul EDENRED reste à ouvrir.
    expect(ghost.textContent).toContain('1 dossier non ouvert');
    expect(ghost.textContent).toContain('dev');
  });

  it('compte TOUS les dossiers non ouverts, sans liste qui ferait grandir la page', () => {
    service = spyService();
    service.runnerHostsOverview.and.returnValue(of([poste]));
    service.runnerHostFolders.and.returnValue(of({
      path: '',
      parentPath: null,
      folders: Array.from({ length: 12 }, (_, i) => ({
        name: `dossier-${i}`, path: `dossier-${i}`, used: false,
      })),
      truncated: false,
    }));
    build();

    expect(text()).toContain('12 dossiers non ouverts');
    expect(text()).not.toContain('dossier-3');
  });

  it('dit quand la machine elle-même a tronqué sa liste : le compte est un minimum', () => {
    service = spyService();
    service.runnerHostsOverview.and.returnValue(of([poste]));
    service.runnerHostFolders.and.returnValue(of({
      path: '', parentPath: null,
      folders: [{ name: 'EDENRED', path: 'EDENRED', used: false }],
      truncated: true,
    }));
    build();

    expect(text()).toContain('1+ dossiers non ouverts');
  });

  it('« Parcourir » ouvre l\'explorateur « Ajouter un projet » du poste', () => {
    setup();

    ((fixture.nativeElement as HTMLElement).querySelector('.poste__ghost-browse') as HTMLButtonElement)
      .click();

    expect(dialog.open).toHaveBeenCalledTimes(1);
    expect(dialog.open.calls.mostRecent().args[1]?.data).toEqual({ hostId: 'h1', hostName: 'Poste CAGIP' });
  });

  it('ne lit AUCUN dossier sur un poste déconnecté, et n\'affiche pas de tuile fantôme', () => {
    setup([{ ...poste, connected: false }]);

    // Personne ne peut lister sans machine : un « 0 dossier » ferait croire à une racine vide.
    expect(service.runnerHostFolders).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).querySelector('.poste__ghost')).toBeNull();
  });

  it('ne lit aucun dossier pour le poste « Hébergé » : il n\'a pas de machine', () => {
    setup([{
      id: null, name: 'Hébergé', virtual: true, connected: false, activeProjects: 0,
      createdAt: new Date().toISOString(), projects: [],
    } as RunnerHostOverview]);

    expect(service.runnerHostFolders).not.toHaveBeenCalled();
    expect(text()).not.toContain('Ajouter un projet');
  });

  it('ne relit PAS les dossiers au sondage de 15 secondes', fakeAsync(() => {
    setup();
    expect(service.runnerHostFolders).toHaveBeenCalledTimes(1);

    tick(POSTES_REFRESH_MS);

    // 240 lectures par heure et par poste, pour une liste qui ne bouge presque jamais — et chacune
    // est une ligne d'audit sur la machine du client.
    expect(service.runnerHostFolders).toHaveBeenCalledTimes(1);
    fixture.destroy();
  }));

  it('relit les dossiers quand l\'utilisateur DEMANDE un rafraîchissement', () => {
    setup();
    service.runnerHostFolders.calls.reset();

    component.refresh();

    // C'est le geste par lequel on dit « j'ai lancé le runner » ou « j'ai créé un dossier ».
    expect(service.runnerHostFolders).toHaveBeenCalledTimes(1);
  });

  it('n\'ouvre rien sur le poste « Hébergé », même si on le lui demande', () => {
    const heberge = {
      id: null, name: 'Hébergé', virtual: true, connected: false, activeProjects: 0,
      createdAt: new Date().toISOString(), projects: [],
    } as RunnerHostOverview;
    setup([heberge]);

    component.addProject(heberge);

    expect(dialog.open).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).querySelector('.poste__ghost')).toBeNull();
  });

  // ---------------- les sources sans machine, sur la carte « Hébergé » (F-72 / SF-72-04)

  it('rend la carte « Hébergé » même quand la gateway n\'en renvoie aucune', () => {
    setup();
    openHost('heberge');

    // Elle porte des GESTES : une carte de gestes qui disparaît quand elle est vide met ses
    // gestes hors de portée.
    expect(text()).toContain('Hébergé');
    expect(text()).toContain('Ouvrir un dépôt GitHub');
    expect(text()).toContain('Importer une archive');
  });

  it('la rend aussi quand aucune machine n\'est connectée — le premier jour', () => {
    setup([]);

    expect(text()).toContain('Aucun poste connecté');
    expect(text()).toContain('Ouvrir un dépôt GitHub');
  });

  it('ne met sur elle AUCUN geste de machine', () => {
    setup([]);
    const card = (fixture.nativeElement as HTMLElement).querySelector('.poste') as HTMLElement;

    expect(card.textContent).not.toContain('Ajouter un projet');
    expect(card.textContent).not.toContain('Supprimer le poste');
    expect(card.querySelector('.poste__mission')).toBeNull();
    expect(card.querySelector('.poste__ghost')).toBeNull();
  });

  it('ouvre un dépôt GitHub puis relit la vue', () => {
    setup();
    dialog.open.and.returnValue({
      afterClosed: () => of({ repoUrl: 'https://github.com/octocat/hello', branch: 'main' }),
    } as never);
    service.runnerHostsOverview.calls.reset();

    component.openGitRepo();

    expect(service.createGitWorkspace).toHaveBeenCalledWith({
      repoUrl: 'https://github.com/octocat/hello', branch: 'main',
    });
    expect(component.creating()).toBeFalse();
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(1);
  });

  it('n\'appelle rien si le dialogue de dépôt est fermé sans choix', () => {
    setup();
    dialog.open.and.returnValue({ afterClosed: () => of(undefined) } as never);

    component.openGitRepo();

    expect(service.createGitWorkspace).not.toHaveBeenCalled();
    expect(component.creating()).toBeFalse();
  });

  it('reprend les messages GitHub à l\'identique', () => {
    setup();
    dialog.open.and.returnValue({
      afterClosed: () => of({ repoUrl: 'https://github.com/octocat/hello' }),
    } as never);
    service.createGitWorkspace.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { error: 'git_token_missing' },
    })));

    component.openGitRepo();

    expect(component.creating()).toBeFalse();
  });

  it('importe une archive après avoir demandé son nom', () => {
    setup();
    dialog.open.and.returnValue({ afterClosed: () => of('mon-archive') } as never);
    const file = new File(['zip'], 'projet.zip', { type: 'application/zip' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;
    service.runnerHostsOverview.calls.reset();

    component.onZipPicked(event);

    // Le nom EST demandé ici, et c'est cohérent : une archive n'a pas de dossier sur une machine
    // dont on pourrait tirer son nom.
    expect(service.createWorkspace).toHaveBeenCalledWith(file, 'mon-archive');
    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(1);
  });

  it('refuse une archive trop volumineuse AVANT tout appel', () => {
    setup();
    const file = new File(['x'], 'gros.zip', { type: 'application/zip' });
    Object.defineProperty(file, 'size', { value: 2_000_000_000 });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    component.onZipPicked(event);

    expect(service.createWorkspace).not.toHaveBeenCalled();
    expect(component.creating()).toBeFalse();
  });

  it('n\'importe rien si la saisie du nom est annulée', () => {
    setup();
    dialog.open.and.returnValue({ afterClosed: () => of(undefined) } as never);
    const file = new File(['zip'], 'projet.zip', { type: 'application/zip' });

    component.onZipPicked({ target: { files: [file], value: 'x' } } as unknown as Event);

    expect(service.createWorkspace).not.toHaveBeenCalled();
  });

  // -------------------------------------------- terminal du poste (F-74 / SF-74-02)

  it('offre « Terminal du poste » sur une machine, et jamais sur « Hébergé »', () => {
    setup();
    const root = fixture.nativeElement as HTMLElement;
    const machine = root.querySelector('.poste:not(.poste--heberge)') as HTMLElement;

    expect(machine.querySelector('.poste__host-terminal')).not.toBeNull();

    openHost('heberge');
    const heberge = root.querySelector('.poste--heberge') as HTMLElement;
    // « Hébergé » n'est pas une machine (F-71) : un terminal de poste n'y voudrait rien dire.
    expect(heberge.querySelector('.poste__host-terminal')).toBeNull();
  });

  it('ouvre le terminal du poste, puis navigue vers lui', () => {
    setup();
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');

    component.openHostTerminal(poste);

    expect(service.openHostTerminal).toHaveBeenCalledWith('h1');
    expect(navigate).toHaveBeenCalledWith(['/atelier', 'wt1']);
    expect(component.openingTerminalHostId()).toBeNull();
  });

  it('ne navigue PAS quand l\'ouverture du terminal du poste échoue', () => {
    setup();
    service.openHostTerminal.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 0 })));
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');

    component.openHostTerminal(poste);

    // Partir vers un terminal qu'on n'a pas obtenu afficherait une page d'erreur à la place d'un
    // message, et perdrait la carte au passage.
    expect(navigate).not.toHaveBeenCalled();
    expect(component.openingTerminalHostId()).toBeNull();
  });

  it('ne tente rien sur le poste « Hébergé »', () => {
    setup();

    component.openHostTerminal(component.hostedHost());

    expect(service.openHostTerminal).not.toHaveBeenCalled();
  });

  // ------------------------------------------------------------------ F-89 / SF-89-03
  // LE TERMINAL TEAMS SUR LA CARTE DU POSTE.
  //
  // Ce qui s'y joue tient en une phrase du cadrage : « son propre droit — il existe, ou il
  // n'existe pas ». Pas de bouton grisé, pas de bouton qui mène à un refus. Un bouton qui mène à
  // un 403 n'est pas une porte, c'est un piège.

  /** Le bouton « Terminal Teams » de la première carte réelle, ou `null`. */
  function teamsButton(): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement)
      .querySelector('.poste:not(.poste--heberge) .poste__teams-terminal');
  }

  it('SANS LE DROIT, il n\'y a PAS DE BOUTON — ni grisé, ni menant à un refus', () => {
    setup();

    expect(teamsButton()).toBeNull();
  });

  it('avec le droit, la carte d\'un poste réel porte « Terminal Teams »', () => {
    service = spyService();
    service.runnerHostsOverview.and.returnValue(of([poste]));
    service.teamsAccess.and.returnValue(of({ entitled: true }));
    build();

    expect(teamsButton()).not.toBeNull();
    expect(teamsButton()?.textContent).toContain('Teams');
  });

  it('« Hébergé » ne le porte jamais : ce n\'est pas une machine, aucun navigateur à observer', () => {
    service = spyService();
    service.runnerHostsOverview.and.returnValue(of([poste]));
    service.teamsAccess.and.returnValue(of({ entitled: true }));
    build();

    expect(component.showTeamsTerminal(component.hostedHost())).toBeFalse();
  });

  it('FAIL-CLOSED : une lecture du droit qui échoue laisse le bouton absent', () => {
    service = spyService();
    service.runnerHostsOverview.and.returnValue(of([poste]));
    service.teamsAccess.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    build();

    expect(component.teamsEntitled()).toBeFalse();
    expect(teamsButton()).toBeNull();
  });

  it('ouvre le terminal Teams, puis navigue vers lui', () => {
    setup();
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');

    component.openTeamsTerminal(poste);

    expect(service.openTeamsTerminal).toHaveBeenCalledWith('h1');
    expect(navigate).toHaveBeenCalledWith(['/atelier', 'wtt1']);
    expect(component.openingTeamsHostId()).toBeNull();
  });

  it('ne navigue PAS quand l\'ouverture échoue', () => {
    setup();
    service.openTeamsTerminal.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 0 })));
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');

    component.openTeamsTerminal(poste);

    expect(navigate).not.toHaveBeenCalled();
    expect(component.openingTeamsHostId()).toBeNull();
  });

  it('montre la pastille de vie quand un onglet vit sur le terminal Teams', () => {
    service = spyService();
    service.runnerHostsOverview.and.returnValue(
      of([{ ...poste, teamsTerminalId: 'wtt1', teamsTerminalLive: true }]));
    service.teamsAccess.and.returnValue(of({ entitled: true }));
    build();

    // La MÊME pastille que partout ailleurs (F-70) : aucun registre de couleur de plus.
    expect(teamsButton()?.querySelectorAll('app-live-badge').length).toBe(1);
  });

  it('montre la pastille de vie quand un onglet vit sur le terminal du poste', () => {
    setup([{ ...poste, hostTerminalId: 'wt1', hostTerminalLive: true }]);
    const button = (fixture.nativeElement as HTMLElement)
      .querySelector('.poste:not(.poste--heberge) .poste__host-terminal') as HTMLElement;

    // La MÊME pastille que partout ailleurs (F-70) : aucun quatrième registre de couleur.
    expect(button.querySelector('app-live-badge')).not.toBeNull();
  });

  // ------------------------------------ refus d'accès (F-85 / SF-85-04)

  /**
   * **Le test de la feature** : sur LE MÊME geste, un refus d'accès et une gateway tombée doivent
   * se dire différemment — sans quoi l'un des deux ment.
   */
  describe("un accès refusé dit pourquoi, et où aller", () => {
    /** Remplace la snackbar par un espion dont l'action est observable. */
    function spySnackBar(): jasmine.Spy {
      const snackBar = TestBed.inject(MatSnackBar);
      return spyOn(snackBar, 'open').and.returnValue({
        onAction: () => of(undefined),
      } as never);
    }

    function lastMessage(open: jasmine.Spy): string {
      return open.calls.mostRecent().args[0] as string;
    }

    it("nomme les deux sorties sur un 403, et conduit à la section du code", () => {
      setup();
      const open = spySnackBar();
      const router = TestBed.inject(Router);
      const navigate = spyOn(router, 'navigate');
      service.openHostTerminal.and.returnValue(
        throwError(() => new HttpErrorResponse({
          status: 403,
          error: { error: 'atelier_forbidden', message: "La Forge demande l'offre Gold." },
        })),
      );

      component.openHostTerminal(poste);

      const message = lastMessage(open);
      expect(message).toContain("n'est pas ouvert");
      expect(message).toContain('souscrire');
      expect(message).toContain("code d'accès");
      // L'action de la snackbar conduit à l'endroit EXACT où le code se saisit.
      expect(navigate).toHaveBeenCalledWith(['/billing'], { fragment: 'code-acces' });
    });

    it("dit AUTRE CHOSE quand c'est la gateway qui est tombée, sur le même geste", () => {
      setup();
      const open = spySnackBar();
      service.openHostTerminal.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 0 })),
      );

      component.openHostTerminal(poste);

      const message = lastMessage(open);
      expect(message).toContain("n'a pas pu être ouvert");
      expect(message).not.toContain("code d'accès");
    });

    it("distingue aussi le 403 sur l'état de mission, qui ne regardait aucun statut", () => {
      setup();
      const open = spySnackBar();
      service.setHostMissionStatus.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 403 })),
      );

      component.setMission(poste, 'CLOSED');

      expect(lastMessage(open)).toContain("code d'accès");
    });

    it('le panneau de refus nomme le code et pointe la section, pas seulement la page', () => {
      service = jasmine.createSpyObj<AtelierService>('AtelierService',
        ['runnerHostsOverview', 'setHostMissionStatus', 'teamsAccess']);
      service.teamsAccess.and.returnValue(of({ entitled: false }));
      service.runnerHostsOverview.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 403 })),
      );
      build();

      expect(text()).toContain("code d'accès");
      expect(text()).toContain('souscrire');
      const link = (fixture.nativeElement as HTMLElement)
        .querySelector('.postes__notice-actions a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/billing#code-acces');
    });
  });

  // --------------------------------------------- la carte du poste (F-92 / SF-92-03)

  describe('la carte du poste', () => {
    // F-98 / SF-98-02 : la carte vit dans son onglet.
    beforeEach(() => initialTab = 'carte');

    it('montre ce que la machine sait, sans ouvrir un terminal', () => {
      setup();

      expect(governance.getMap).toHaveBeenCalledOnceWith('h1');
      expect(text()).toContain('Carte du poste');
      expect(text()).toContain('3 fait(s)');
      expect(text()).toContain('La carte du poste');
      expect(text()).toContain('Accès');
      // Un fichier encore vide le DIT : c'est ce qui montre où la connaissance manque.
      expect(text()).toContain('encore vide');
    });

    it("dit à quoi sert le terminal du poste — aujourd'hui, rien ne l'indique", () => {
      setup();

      expect(text()).toContain('terminal du poste');
      expect(text()).toContain('à la racine');
    });

    it('un poste NON CONNECTÉ ne déclenche aucune lecture, et le dit avec son geste', () => {
      setup([{ ...poste, connected: false }]);

      expect(governance.getMap).not.toHaveBeenCalled();
      expect(text()).toContain('lancez le runner');
    });

    it("le poste « Hébergé » n'a pas de carte : ce n'est pas une machine", () => {
      setup([]);

      const heberge = (fixture.nativeElement as HTMLElement).querySelector('.poste--heberge');
      expect(heberge).not.toBeNull();
      expect(heberge?.querySelector('.poste__carte')).toBeNull();
    });

    it('le sondage de 15 s ne relit JAMAIS la carte ; « Rafraîchir » la relit', fakeAsync(() => {
      setup();
      expect(governance.getMap).toHaveBeenCalledTimes(1);

      tick(POSTES_REFRESH_MS);
      fixture.detectChanges();
      // Six lectures de fichier par poste toutes les 15 s, ce sont 1 440 allers-retours par heure
      // sur la machine d'un client : le sondage ne les fait pas.
      expect(governance.getMap).toHaveBeenCalledTimes(1);

      component.refresh();
      expect(governance.getMap).toHaveBeenCalledTimes(2);

      fixture.destroy();
      tick(POSTES_REFRESH_MS);
    }));

    it("un poste non gouverné rend le geste qui le gouverne, pas une carte vide", () => {
      governanceReturns({
        ...carte,
        governed: false,
        readable: false,
        files: [],
        filesExpected: 0,
        filesPresent: 0,
        facts: 0,
        message: 'Aucune gouvernance active sur ce poste : activez « Le savoir durable ».',
      });

      expect(text()).toContain('Aucune gouvernance active');
      expect(text()).toContain('Ouvrir la gouvernance');
    });

    it('un échec de lecture fait disparaître la section, SANS rouge', () => {
      service = spyService();
      service.runnerHostsOverview.and.returnValue(of([poste]));
      buildWithMap(throwError(() => new HttpErrorResponse({ status: 500 })));

      // La carte du poste, elle, reste exacte : un rouge ici enverrait chercher au mauvais endroit.
      expect(text()).toContain('Poste CAGIP');
      expect(text()).not.toContain('fait(s)');
    });

    it('cliquer un fichier de la carte ouvre son contenu', () => {
      setup();

      component.openMapFile(poste, carte.files[0]);

      expect(dialog.open).toHaveBeenCalled();
      const data = dialog.open.calls.mostRecent().args[1]?.data as { hostRef: string; file: unknown };
      expect(data.hostRef).toBe('h1');
      expect(data.file).toBe(carte.files[0]);
    });

    // ------------------------------- ce que la carte a gagné (F-93 / SF-93-03)

    /** Un instant ISO daté de `days` jours en arrière. */
    function daysAgo(days: number): string {
      return new Date(Date.now() - days * 86_400_000).toISOString();
    }

    it('un gain se CONSTATE : la phrase dit depuis quand, et de combien à combien', () => {
      governanceReturns({
        ...carte,
        facts: 16,
        growth: {
          since: new Date('2026-09-02T08:00:00Z').toISOString(),
          sinceFacts: 4,
          gained: 12,
          recent: [
            { path: 'acces.md', title: 'Accès', gained: 3, gainedAt: daysAgo(2) },
            { path: 'README.md', title: 'La carte', gained: 1, gainedAt: daysAgo(5) },
          ],
        },
      });

      expect(text()).toContain('2 septembre');
      expect(text()).toContain('de 4 à 16 fait(s)');
      expect(text()).toContain('acces.md +3');
      expect(text()).toContain('il y a 2 j');
    });

    it("sans gain, RIEN ne s'affiche : un « +0 » quotidien apprendrait qu'on ne gagne rien", () => {
      governanceReturns({
        ...carte,
        growth: { since: daysAgo(3), sinceFacts: 3, gained: 0, recent: [] },
      });

      expect(text()).not.toContain('cette carte est passée');
    });

    it('un relevé SANS bloc de croissance ne change rien à ce qui était affiché', () => {
      governanceReturns({ ...carte, growth: null });

      expect(text()).toContain('Carte du poste');
      expect(text()).not.toContain('cette carte est passée');
    });

    it('trois lignes au plus : au-delà ce ne serait plus un constat, mais une liste', () => {
      const recent = [1, 2, 3, 4, 5].map((n) => ({
        path: `fichier-${n}.md`,
        title: `Fichier ${n}`,
        gained: n,
        gainedAt: daysAgo(n),
      }));
      governanceReturns({ ...carte, facts: 20, growth: { since: daysAgo(9), sinceFacts: 5, gained: 15, recent } });

      const lines = (fixture.nativeElement as HTMLElement)
        .querySelectorAll('.poste__carte-gain-list li');
      expect(lines.length).toBe(3);
      expect(lines[0].textContent).toContain('fichier-1.md');
    });

    it("une date illisible n'affiche JAMAIS « NaN » — la phrase reste vraie sans sa date", () => {
      governanceReturns({
        ...carte,
        facts: 9,
        growth: {
          since: 'pas-une-date',
          sinceFacts: 2,
          gained: 7,
          recent: [{ path: 'acces.md', title: 'Accès', gained: 7, gainedAt: 'pas-une-date' }],
        },
      });

      expect(text()).not.toContain('NaN');
      expect(text()).not.toContain('Invalid');
      expect(text()).toContain('cette carte a gagné 7 fait(s)');
      expect(text()).toContain('acces.md +7');
    });

    it('le bloc ne coûte AUCUN appel de plus : il lit le relevé déjà chargé', () => {
      governanceReturns({
        ...carte,
        facts: 16,
        growth: {
          since: daysAgo(10),
          sinceFacts: 4,
          gained: 12,
          recent: [{ path: 'acces.md', title: 'Accès', gained: 3, gainedAt: daysAgo(1) }],
        },
      });

      expect(governance.getMap).toHaveBeenCalledTimes(1);
    });

    /** Rejoue l'écran avec un relevé de carte donné. */
    function governanceReturns(map: GovernanceMap): void {
      service = spyService();
      service.runnerHostsOverview.and.returnValue(of([poste]));
      buildWithMap(of(map));
    }

    /** Construit l'écran en imposant ce que la lecture de carte répond. */
    function buildWithMap(answer: Observable<GovernanceMap>): void {
      governance = jasmine.createSpyObj<GovernanceService>('GovernanceService',
        ['getMap', 'readMapFile', 'getIntegrite', 'getHosts']);
      governance.getMap.and.returnValue(answer);
      governance.getIntegrite.and.returnValue(of(integriteSaine));
      governance.getHosts.and.returnValue(of([]));
      dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
      dialog.open.and.returnValue({ afterClosed: () => of(dialogAnswer) } as never);
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        imports: [PostesComponent],
        providers: [
          { provide: AtelierService, useValue: service },
          { provide: GovernanceService, useValue: governance },
          { provide: MatDialog, useValue: dialog },
          provideRouter([]),
          provideNoopAnimations(),
          { provide: ActivatedRoute, useValue: routeMock(null) },
        ],
      });
      fixture = TestBed.createComponent(PostesComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    }
  });

  // ------------------------------------- l'intégrité du poste (F-95 / SF-95-03)

  describe("l'intégrité du poste", () => {
    // F-98 / SF-98-02 : l'intégrité quitte la carte pour l'onglet Gouvernance.
    beforeEach(() => initialTab = 'gouvernance');

    it('se lit avec la carte, une fois par page, sur un poste connecté', () => {
      setup();

      expect(governance.getIntegrite).toHaveBeenCalledOnceWith('h1');
    });

    it("n'appelle rien pour un poste NON CONNECTÉ", () => {
      setup([{ ...poste, connected: false }]);

      expect(governance.getIntegrite).not.toHaveBeenCalled();
    });

    it("un poste sain ne dit rien : un « aucune erreur » permanent deviendrait invisible", () => {
      setup();

      expect(text()).not.toContain('Intégrité —');
    });

    it("un poste NON INSPECTÉ ne dit rien non plus — ce n'est pas « tout va bien »", () => {
      setup();
      governance.getIntegrite.and.returnValue(of({
        ...integriteSaine,
        inspected: false,
        errors: [{ rule: 'carte/fichier-absent', target: 'reseau.md', message: 'jamais lu' }],
      }));
      component.refresh();
      fixture.detectChanges();

      expect(text()).not.toContain('jamais lu');
    });

    it('montre les deux niveaux SÉPARÉS, et reprend le message du serveur tel quel', () => {
      setup();
      governance.getIntegrite.and.returnValue(of({
        ...integriteSaine,
        errors: [{
          rule: 'carte/fichier-absent',
          target: 'reseau.md',
          message: 'le fichier de carte « reseau.md » manque : reprends « Appliquer ».',
        }],
        warnings: [{
          rule: 'dette/en-cours',
          target: 'migration-dns',
          message: 'le projet « migration-dns » garde 2 cases : promeus-les au fil de l\u2019eau.',
        }],
      }));
      component.refresh();
      fixture.detectChanges();

      expect(text()).toContain('Intégrité — à corriger');
      expect(text()).toContain('Intégrité — à surveiller');
      expect(text()).toContain('reprends « Appliquer »');
      expect(text()).toContain('promeus-les au fil de l');
    });

    it('un échec de lecture reste SILENCIEUX : un rouge ici enverrait au mauvais endroit', () => {
      setup();
      governance.getIntegrite.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })));
      component.refresh();
      fixture.detectChanges();

      expect(text()).not.toContain('Intégrité —');
      expect(text()).toContain('Rien à appliquer');
    });
  });

});

/** Le DOM rend les couleurs en `rgb(...)` : on compare ce qu'il rend, pas ce qu'on a écrit. */
function hexToRgb(hex: string): string {
  const value = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16));
  return `rgb(${r}, ${g}, ${b})`;
}
