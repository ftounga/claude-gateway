import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';

import { MatDialog } from '@angular/material/dialog';

import { POSTES_REFRESH_MS, PostesComponent } from './postes.component';
import { AtelierService } from '../core/services/atelier.service';
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
  let dialog: jasmine.SpyObj<MatDialog>;
  /** Ce que le dialogue de suppression renvoie : `true` = l'utilisateur a confirmé. */
  let dialogAnswer: boolean;

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
        'killHost']);
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
   * Prépare l'écran avec un fragment d'URL (F-68 / SF-68-01) : c'est ce que pose le niveau
   * « chez qui » du fil d'Ariane quand on revient sur l'accueil de la Forge.
   */
  function setupWithFragment(fragment: string, hosts: RunnerHostOverview[] = [poste]): void {
    service = spyService();
    service.runnerHostsOverview.and.returnValue(of(hosts));
    build(fragment);
  }

  function build(fragment: string | null = null): void {
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.returnValue({ afterClosed: () => of(dialogAnswer) } as never);
    TestBed.configureTestingModule({
      imports: [PostesComponent],
      providers: [
        { provide: AtelierService, useValue: service },
        { provide: MatDialog, useValue: dialog },
        provideRouter([]),
        provideNoopAnimations(),
        // Déclaré APRÈS `provideRouter` : c'est ce jeton-là que l'écran lit pour son ancrage.
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    });
    fixture = TestBed.createComponent(PostesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
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
    expect(text()).toContain('Connecté');
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

  it('porte la couleur du poste sur le filet de sa carte ET sur celui de chaque projet', () => {
    setup();
    const root = fixture.nativeElement as HTMLElement;
    const card = root.querySelector('.poste') as HTMLElement;
    const projects = root.querySelectorAll<HTMLElement>('.projet');

    expect(card.style.borderLeftColor).not.toBe('');
    expect(projects.length).toBe(2);
    projects.forEach((project) => {
      // Le projet reprend le filet de SA machine : c'est ce qui le rattache visuellement.
      expect(project.style.borderLeftColor).toBe(card.style.borderLeftColor);
    });
  });

  it('donne à deux postes de noms différents deux couleurs différentes', () => {
    setup([
      { ...poste, id: 'h1', name: 'Poste bureau' },
      { ...poste, id: 'h2', name: 'Poste maison' },
    ]);
    const cards = (fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLElement>('.poste:not(.poste--heberge)');

    expect(cards.length).toBe(2);
    expect(cards[0].style.borderLeftColor).not.toBe(cards[1].style.borderLeftColor);
  });

  it('ÉCRIT le nom du poste à côté de sa couleur — elle ne porte jamais seule l\'information', () => {
    setup();
    const heading = (fixture.nativeElement as HTMLElement).querySelector('.poste h2');

    expect(heading?.textContent?.trim()).toBe('Poste CAGIP');
  });

  it('tire la même couleur que la fonction pure partagée', () => {
    setup();
    const card = (fixture.nativeElement as HTMLElement).querySelector('.poste') as HTMLElement;
    const expected = hostTone('Poste CAGIP').solid.toLowerCase();
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(expected.slice(i, i + 2), 16));

    expect(card.style.borderLeftColor).toContain(`${r}, ${g}, ${b}`);
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

  it('rend les projets du poste, dans l’ordre donné par la gateway', () => {
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

  it('nomme la racine quand le projet n’a pas de sous-dossier', () => {
    setup();
    expect(component.projectPathLabel(poste.projects[1])).toBe('la racine');
    expect(component.projectPathLabel(poste.projects[0])).toBe('web');
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
    setup();
    expect(component.hostStateLabel({ ...poste, connected: false })).toContain('Vu il y a');
    expect(component.hostStateLabel({ ...poste, connected: false, lastSeenAt: null }))
      .toBe('Jamais connecté');
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
    service = jasmine.createSpyObj<AtelierService>('AtelierService', ['runnerHostsOverview', 'setHostMissionStatus']);
    service.runnerHostsOverview.and.returnValue(throwError(() => new Error('réseau')));
    build();

    expect(component.error()).toBe('network');
    expect(text()).toContain('Réessayer');
  });

  it("dit que la vue appartient à la Forge quand l'accès est refusé", fakeAsync(() => {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', ['runnerHostsOverview', 'setHostMissionStatus']);
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
    const cards = (fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLElement>('.poste:not(.poste--heberge)');

    expect(cards.length).toBe(2);
    expect(cards[0].style.borderLeftColor).toBe(hexToRgb(hostTone('Poste CAGIP').solid));
    expect(cards[1].style.borderLeftColor).toBe(hexToRgb(hostTone('Poste Bercy').solid));
    expect(cards[0].style.borderLeftColor).not.toBe(cards[1].style.borderLeftColor);
  });

  it("donne le même filet à un même nom, quels que soient les états de mission", () => {
    setup([mission('h1', 'Poste CAGIP', 'ACTIVE'), mission('h2', 'Poste CAGIP', 'CLOSED')]);
    component.closedOpen.set(true);
    fixture.detectChanges();
    const cards = (fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLElement>('.poste:not(.poste--heberge)');

    expect(cards.length).toBe(2);
    expect(cards[0].style.borderLeftColor).toBe(cards[1].style.borderLeftColor);
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
    component.toggleClosed();
    fixture.detectChanges();

    const buttons = (fixture.nativeElement as HTMLElement)
      .querySelectorAll('.projet button[aria-label^="Ouvrir le terminal"]');
    expect(buttons.length).toBe(2);
  });

  // ------------------------------------- accueil de la Forge (F-68 / SF-68-01)

  describe("accueil de la Forge (F-68)", () => {
    it('porte le fil d\'Ariane, et s\'y nomme « Forge »', () => {
      setup();
      const crumbs = (fixture.nativeElement as HTMLElement).querySelector('app-forge-breadcrumb');

      expect(crumbs).not.toBeNull();
      expect(crumbs?.textContent).toContain('Forge');
      // Cet écran EST l'accueil de la Forge : c'est le dernier niveau.
      expect(crumbs?.querySelector('[aria-current="page"]')?.textContent?.trim()).toBe('Forge');
    });

    it('donne à chaque carte l\'ancrage que vise le niveau « chez qui »', () => {
      setup();

      expect((fixture.nativeElement as HTMLElement).querySelector('#poste-h1')).not.toBeNull();
    });

    it('amène dans le champ de vision la carte visée par le fragment', () => {
      setupWithFragment('poste-h1');
      const card = (fixture.nativeElement as HTMLElement).querySelector('#poste-h1') as HTMLElement;
      const scroll = spyOn(card, 'scrollIntoView');

      component.revealAnchoredHost();

      expect(scroll).toHaveBeenCalled();
    });

    it('ne bronche pas sur un fragment qui ne désigne aucune carte', () => {
      // Poste supprimé, mission clôturée et repliée, fragment recopié de travers : un fil d'Ariane
      // ne doit jamais produire d'erreur.
      setupWithFragment('poste-inconnu');

      expect(() => component.revealAnchoredHost()).not.toThrow();
    });

    it('n\'honore l\'ancrage qu\'une fois — ensuite l\'écran appartient à l\'utilisateur', () => {
      setupWithFragment('poste-h1');
      const card = (fixture.nativeElement as HTMLElement).querySelector('#poste-h1') as HTMLElement;
      const scroll = spyOn(card, 'scrollIntoView');

      component.revealAnchoredHost();
      component.revealAnchoredHost();

      expect(scroll).toHaveBeenCalledTimes(1);
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
      expect(counter?.textContent).toContain('Terminaux vivants : 2 / 4');
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
      expect(dom.querySelector('.postes__live')?.textContent).toContain('Terminaux vivants : 1 / 4');
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
      expect((fixture.nativeElement as HTMLElement)
        .querySelector('.poste__head-side button:not(.poste__menu-trigger)')).toBeNull();
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

  it('range les projets sans machine sous « Hébergé », en dernier', () => {
    setup([poste, heberge]);
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.poste');

    expect(cards.length).toBe(2);
    // Toujours EN DERNIER, après les machines.
    expect(cards[1].textContent).toContain('Hébergé');
    expect(cards[1].textContent).toContain('mon-depot');
    expect(cards[1].classList).toContain('poste--heberge');
  });

  it('ne lui donne ni identité de machine, ni état de connexion, ni mission, ni suppression', () => {
    setup([heberge]);
    const card = (fixture.nativeElement as HTMLElement).querySelector('.poste') as HTMLElement;

    // Le §9 réserve ses dix tons à l'identification d'une MACHINE : le poste virtuel n'en prend
    // aucun, et ne porte donc pas la pastille d'initiales.
    expect(component.tone(heberge)).toBeNull();
    expect(card.querySelector('.host-badge__mark')).toBeNull();
    // Ni « Connecté », ni « Jamais connecté » : il n'a pas de runner.
    expect(card.textContent).not.toContain('Connecté');
    expect(card.textContent).not.toContain('Jamais connecté');
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

  it('n\'ancre pas la carte virtuelle : elle n\'a pas d\'identifiant', () => {
    setup([heberge]);
    const card = (fixture.nativeElement as HTMLElement).querySelector('.poste') as HTMLElement;

    expect(card.id).toBe('');
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

  it('montre les dossiers de la racine QUI NE SONT PAS encore ouverts', () => {
    setup();
    const items = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.poste__unopened-item'),
    ).map((node) => node.textContent?.trim() ?? '');

    // `web` est déjà pris par un projet (`used`) : le proposer ferait ouvrir deux fois le même
    // dossier, c'est-à-dire le défaut que F-72 supprime.
    expect(items.some((label) => label.includes('EDENRED'))).toBeTrue();
    expect(items.some((label) => label.includes('web'))).toBeFalse();
  });

  it('tronque la liste des dossiers au seuil, et le DIT', () => {
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

    const items = (fixture.nativeElement as HTMLElement)
      .querySelectorAll('.poste__unopened-item');
    expect(items.length).toBe(component.maxUnopenedShown);
    // Une liste incomplète se DIT (SF-38-21).
    expect(text()).toContain('et 4 autres');
  });

  it('dit quand la machine elle-même a tronqué sa liste', () => {
    service = spyService();
    service.runnerHostsOverview.and.returnValue(of([poste]));
    service.runnerHostFolders.and.returnValue(of({
      path: '', parentPath: null,
      folders: [{ name: 'EDENRED', path: 'EDENRED', used: false }],
      truncated: true,
    }));
    build();

    expect(text()).toContain('tronquée');
  });

  it('ne lit AUCUN dossier sur un poste déconnecté, et n\'affiche pas la section', () => {
    setup([{ ...poste, connected: false }]);

    // Personne ne peut lister sans machine : une liste vide ferait croire à une racine sans
    // sous-dossier.
    expect(service.runnerHostFolders).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).querySelector('.poste__unopened')).toBeNull();
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

  it('ouvre un projet d\'un clic sur un dossier non ouvert, sans demander de nom', () => {
    setup();

    component.openFolderAsProject(poste, { name: 'EDENRED', path: 'EDENRED', used: false });

    expect(service.openHostProject).toHaveBeenCalledWith('h1', 'EDENRED');
  });

  it('relit la vue après une ouverture', () => {
    setup();
    service.runnerHostsOverview.calls.reset();

    component.openFolderAsProject(poste, { name: 'EDENRED', path: 'EDENRED', used: false });

    expect(service.runnerHostsOverview).toHaveBeenCalledTimes(1);
  });

  it('reprend le refus de doublon de la gateway, tel quel', () => {
    setup();
    service.openHostProject.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409,
      error: { error: 'host_project_exists', message: 'Ce dossier est déjà ouvert : EDENRED.' },
    })));

    component.openFolderAsProject(poste, { name: 'EDENRED', path: 'EDENRED', used: false });

    expect(component.openingFolder()).toBeNull();
  });

  it('n\'ouvre rien sur le poste « Hébergé », même si on le lui demande', () => {
    const heberge = {
      id: null, name: 'Hébergé', virtual: true, connected: false, activeProjects: 0,
      createdAt: new Date().toISOString(), projects: [],
    } as RunnerHostOverview;
    setup([heberge]);

    component.addProject(heberge);
    component.openFolderAsProject(heberge, { name: 'x', path: 'x', used: false });

    expect(dialog.open).not.toHaveBeenCalled();
    expect(service.openHostProject).not.toHaveBeenCalled();
  });

  // ---------------- les sources sans machine, sur la carte « Hébergé » (F-72 / SF-72-04)

  it('rend la carte « Hébergé » même quand la gateway n\'en renvoie aucune', () => {
    setup();

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
    expect(card.querySelector('.poste__unopened')).toBeNull();
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
    const heberge = root.querySelector('.poste--heberge') as HTMLElement;

    expect(machine.querySelector('.poste__host-terminal')).not.toBeNull();
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

  it('montre la pastille de vie quand un onglet vit sur le terminal du poste', () => {
    setup([{ ...poste, hostTerminalId: 'wt1', hostTerminalLive: true }]);
    const actions = (fixture.nativeElement as HTMLElement)
      .querySelector('.poste:not(.poste--heberge) .poste__card-actions') as HTMLElement;

    // La MÊME pastille que partout ailleurs (F-70) : aucun quatrième registre de couleur.
    expect(actions.querySelector('app-live-badge')).not.toBeNull();
  });

});

/** Le DOM rend les couleurs en `rgb(...)` : on compare ce qu'il rend, pas ce qu'on a écrit. */
function hexToRgb(hex: string): string {
  const value = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16));
  return `rgb(${r}, ${g}, ${b})`;
}
