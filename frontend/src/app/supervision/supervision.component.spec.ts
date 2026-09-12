import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { LiveTerminalEntry, LiveTerminals } from '../core/models/atelier.models';
import { SUPERVISION_REFRESH_MS, SupervisionComponent } from './supervision.component';

/**
 * La vue de supervision (F-76 / SF-76-03).
 *
 * <p>Ce qui s'y vérifie d'abord n'est pas la grille : c'est l'<b>exigence non négociable</b> — une
 * tuile qui attend une autorisation se signale <b>trois fois</b> (en tête, par son anneau, par un
 * compteur écrit). Le 2026-09-08, la demande était à l'écran et a échappé douze heures (F-47).</p>
 */
describe('SupervisionComponent', () => {
  let fixture: ComponentFixture<SupervisionComponent>;
  let component: SupervisionComponent;
  let http: HttpTestingController;

  const URL = '/api/terminals/live';

  function terminal(overrides: Partial<LiveTerminalEntry> = {}): LiveTerminalEntry {
    return {
      workspaceId: 'w-1',
      workspaceName: 'web',
      hostId: 'h-1',
      hostName: 'CAGIP',
      openedAt: new Date(Date.now() - 600_000).toISOString(),
      ...overrides,
    };
  }

  function registry(terminals: LiveTerminalEntry[]): LiveTerminals {
    return { limit: 4, live: terminals.length, terminals };
  }

  function setup(terminals: LiveTerminalEntry[]): void {
    fixture.detectChanges();
    http.expectOne(URL).flush(registry(terminals));
    fixture.detectChanges();
  }

  function dom(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SupervisionComponent],
      providers: [
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    fixture = TestBed.createComponent(SupervisionComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
  });

  it('montre une tuile par terminal, avec le projet ET le poste écrits', () => {
    setup([
      terminal(),
      terminal({ workspaceId: 'w-2', workspaceName: 'api', hostName: 'BNP' }),
    ]);

    const tiles = dom().querySelectorAll('.tuile');
    expect(tiles.length).toBe(2);
    expect(dom().textContent).toContain('web');
    expect(dom().textContent).toContain('CAGIP');
    expect(dom().textContent).toContain('BNP');
  });

  it('place EN TÊTE la tuile qui attend une autorisation', () => {
    // Le tri n'est pas cosmétique : c'est le signal qui vaut encore quand la grille déborde.
    setup([
      terminal({ workspaceId: 'w-1', workspaceName: 'web', activity: 'RUNNING' }),
      terminal({
        workspaceId: 'w-2',
        workspaceName: 'api',
        activity: 'AWAITING_APPROVAL',
        activityDetail: 'rm -rf build',
      }),
    ]);

    const tiles = Array.from(dom().querySelectorAll('.tuile'));
    expect(tiles[0].textContent).toContain('api');
    expect(tiles[0].classList).toContain('tuile--awaiting');
    expect(tiles[1].classList).not.toContain('tuile--awaiting');
  });

  it('compte en toutes lettres ce qui attend une décision', () => {
    setup([terminal({ activity: 'AWAITING_APPROVAL', activityDetail: 'git push' })]);

    expect(dom().querySelector('.supervision__awaiting')?.textContent)
      .toContain('1 terminal attend votre autorisation');
    expect(dom().textContent).toContain('Attend votre autorisation');
  });

  it('accorde le compteur au pluriel', () => {
    setup([
      terminal({ workspaceId: 'w-1', activity: 'AWAITING_APPROVAL' }),
      terminal({ workspaceId: 'w-2', activity: 'AWAITING_APPROVAL' }),
    ]);

    expect(dom().textContent).toContain('2 terminaux attendent votre autorisation');
  });

  it('n’écrit aucun compteur quand personne n’attend', () => {
    setup([terminal({ activity: 'RUNNING', activityDetail: 'npm test' })]);

    expect(dom().querySelector('.supervision__awaiting')).toBeNull();
  });

  it('n’emprunte aucun ton d’identité pour un projet sans machine', () => {
    // Les dix tons du §9 identifient une MACHINE : le bac à sable de la gateway n'en est pas une.
    setup([terminal({ hostId: null, hostName: null })]);

    expect(dom().textContent).toContain('Hébergé');
    expect(component.tiles()[0].tone).toBeNull();
  });

  it('affiche la tuile d’un terminal qui n’a encore rien dit', () => {
    setup([terminal()]);

    expect(dom().querySelectorAll('.tuile').length).toBe(1);
    expect(dom().querySelector('.preview')).toBeNull();
  });

  it('mène au terminal d’un clic', () => {
    setup([terminal({ workspaceId: 'w-42' })]);

    expect(dom().querySelector('.tuile')?.getAttribute('href')).toBe('/atelier/w-42');
  });

  it('ne prend AUCUNE place au registre : il lit, il n’ouvre rien', () => {
    // Regarder ses agents ne doit pas coûter un des quatre flux payants.
    setup([terminal()]);

    expect(http.match((request) => request.method === 'POST').length).toBe(0);
  });

  it('garde ses tuiles quand un rafraîchissement échoue, et le dit', () => {
    jasmine.clock().install();
    try {
      setup([terminal()]);

      jasmine.clock().tick(SUPERVISION_REFRESH_MS);
      http.expectOne(URL).error(new ProgressEvent('network'));
      fixture.detectChanges();

      // Vider la grille sur un hoquet réseau ferait croire que les agents se sont arrêtés.
      expect(dom().querySelectorAll('.tuile').length).toBe(1);
      expect(dom().textContent).toContain('Dernier état connu à');
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('dit l’échec du PREMIER chargement, et propose de réessayer', () => {
    fixture.detectChanges();
    http.expectOne(URL).error(new ProgressEvent('network'));
    fixture.detectChanges();

    expect(dom().textContent).toContain("L'état des terminaux n'a pas pu être lu");
    expect(dom().textContent).toContain('Réessayer');
  });

  it('dit ce qui manque quand aucun terminal ne vit', () => {
    setup([]);

    expect(dom().textContent).toContain('Aucun terminal ouvert');
    expect(dom().querySelector('a[href="/forge"]')).not.toBeNull();
  });

  it('rafraîchit toutes les cinq secondes, et s’arrête à la destruction', () => {
    jasmine.clock().install();
    try {
      setup([terminal()]);

      jasmine.clock().tick(SUPERVISION_REFRESH_MS);
      http.expectOne(URL).flush(registry([terminal()]));

      fixture.destroy();
      jasmine.clock().tick(SUPERVISION_REFRESH_MS * 3);

      // Aucune minuterie orpheline : une vue fermée ne sonde plus.
      http.expectNone(URL);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('ne porte aucun champ de saisie : on regarde, on n’écrit pas', () => {
    setup([terminal({ activity: 'RUNNING', activityDetail: 'npm test' })]);

    const grid = dom().querySelector('.supervision__grid');
    expect(grid?.querySelector('input')).toBeNull();
    expect(grid?.querySelector('textarea')).toBeNull();
    expect(grid?.querySelector('button')).toBeNull();
  });

  it('calcule la durée d’ouverture à l’affichage', () => {
    setup([terminal({ openedAt: new Date(Date.now() - 720_000).toISOString() })]);

    expect(dom().textContent).toContain('ouvert depuis 12 min');
  });
});
