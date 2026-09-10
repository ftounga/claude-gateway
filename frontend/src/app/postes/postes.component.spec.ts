import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { POSTES_REFRESH_MS, PostesComponent } from './postes.component';
import { AtelierService } from '../core/services/atelier.service';
import { RunnerHostOverview } from '../core/models/atelier.models';

/**
 * L'écran des postes (F-49 / SF-49-02) : ce qu'il montre, ce qu'il ne fait pas, et ce qu'il ne
 * casse pas quand le réseau hoquette.
 */
describe('PostesComponent', () => {
  let fixture: ComponentFixture<PostesComponent>;
  let component: PostesComponent;
  let service: jasmine.SpyObj<AtelierService>;

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

  function setup(hosts: RunnerHostOverview[] = [poste]): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', ['runnerHostsOverview']);
    service.runnerHostsOverview.and.returnValue(of(hosts));
    build();
  }

  function build(): void {
    TestBed.configureTestingModule({
      imports: [PostesComponent],
      providers: [
        { provide: AtelierService, useValue: service },
        provideRouter([]),
        provideNoopAnimations(),
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
    service = jasmine.createSpyObj<AtelierService>('AtelierService', ['runnerHostsOverview']);
    service.runnerHostsOverview.and.returnValue(throwError(() => new Error('réseau')));
    build();

    expect(component.error()).toBe('network');
    expect(text()).toContain('Réessayer');
  });

  it("dit que la vue appartient à la Forge quand l'accès est refusé", fakeAsync(() => {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', ['runnerHostsOverview']);
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
});
