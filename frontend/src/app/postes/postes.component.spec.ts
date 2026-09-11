import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { MatDialog } from '@angular/material/dialog';

import { POSTES_REFRESH_MS, PostesComponent } from './postes.component';
import { AtelierService } from '../core/services/atelier.service';
import { RunnerHostOverview } from '../core/models/atelier.models';
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
    elevated: false,
    connected: true,
    lastSeenAt: new Date().toISOString(),
    createdAt: new Date().toISOString(),
    lastActivityAt: new Date().toISOString(),
    activeProjects: 1,
    projects: [
      {
        id: 'w1',
        name: 'web',
        projectPath: 'web',
        executionTarget: 'RUNNER',
        lastActivityAt: new Date().toISOString(),
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
    service = jasmine.createSpyObj<AtelierService>('AtelierService',
      ['runnerHostsOverview', 'setHostMissionStatus', 'deleteRunnerHost']);
    service.runnerHostsOverview.and.returnValue(of(hosts));
    build();
  }

  /**
   * Prépare l'écran avec un fragment d'URL (F-68 / SF-68-01) : c'est ce que pose le niveau
   * « chez qui » du fil d'Ariane quand on revient sur l'accueil de la Forge.
   */
  function setupWithFragment(fragment: string, hosts: RunnerHostOverview[] = [poste]): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService',
      ['runnerHostsOverview', 'setHostMissionStatus', 'deleteRunnerHost']);
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
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.poste');
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
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.poste');

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

  it('montre ce que la machine a déclaré : racine, système, interpréteur', () => {
    setup();
    expect(text()).toContain('dev');
    expect(text()).toContain('linux');
    expect(text()).toContain('posix');
  });

  it("omet une ligne plutôt que d'écrire « inconnu » quand le runner n'a rien déclaré", () => {
    setup([{ ...poste, rootName: null, os: null, shell: null }]);
    expect(text()).not.toContain('Racine');
    expect(text()).not.toContain('Système');
    expect(text()).not.toContain('Interpréteur');
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
      .querySelectorAll<HTMLElement>('.poste');

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
      .querySelectorAll<HTMLElement>('.poste');

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

});

/** Le DOM rend les couleurs en `rgb(...)` : on compare ce qu'il rend, pas ce qu'on a écrit. */
function hexToRgb(hex: string): string {
  const value = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16));
  return `rgb(${r}, ${g}, ${b})`;
}
