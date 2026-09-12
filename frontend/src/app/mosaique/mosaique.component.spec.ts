import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { LiveTerminalEntry, LiveTerminals } from '../core/models/atelier.models';
import { MosaiqueComponent } from './mosaique.component';

/**
 * **La mosaïque** (F-83 / SF-83-02) : quatre vrais terminaux en même temps.
 *
 * <p>Trois choses s'y vérifient avant tout le reste, et ce sont les trois que le PO a posées :
 * la tuile montre le <b>contenu réel du flux</b> (et non l'aperçu de F-76) ; le fond est
 * <b>celui du terminal</b> ; et ouvrir cet écran <b>ne consomme aucune place émettrice</b> — c'est
 * le test qui protège son portefeuille.</p>
 */
describe('MosaiqueComponent (F-83 / SF-83-02)', () => {
  let fixture: ComponentFixture<MosaiqueComponent>;
  let component: MosaiqueComponent;
  let http: HttpTestingController;
  let fetchSpy: jasmine.Spy;

  const REGISTRY = '/api/terminals/live';

  /** Les événements que chaque flux de tuile émettra, par projet. */
  let streams: Record<string, string[]>;

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

  /** `fetch` factice : rend le flux SSE prévu pour le projet demandé, sans réseau. */
  function installFetch(): void {
    fetchSpy = spyOn(window, 'fetch').and.callFake((input: RequestInfo | URL) => {
      const url = String(input);
      const id = url.match(/workspaces\/([^/]+)\//)?.[1] ?? '';
      const events = streams[id] ?? ['event:idle\ndata:{"live":false}'];
      const chunk = new TextEncoder().encode(events.map((e) => `${e}\n\n`).join(''));
      let sent = false;
      const response = {
        ok: true,
        body: {
          getReader: () => ({
            read: () =>
              Promise.resolve(
                sent ? { value: undefined, done: true } : ((sent = true), { value: chunk, done: false }),
              ),
          }),
        },
      };
      return Promise.resolve(response as unknown as Response);
    });
  }

  /** Vide les micro-tâches : les flux de tuiles s'y déroulent. */
  async function settle(): Promise<void> {
    for (let i = 0; i < 60; i += 1) {
      await Promise.resolve();
    }
    fixture.detectChanges();
  }

  async function setup(terminals: LiveTerminalEntry[]): Promise<void> {
    fixture.detectChanges();
    http.expectOne(REGISTRY).flush(registry(terminals));
    fixture.detectChanges();
    await settle();
  }

  function dom(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    streams = {};
    installFetch();
    TestBed.configureTestingModule({
      imports: [MosaiqueComponent],
      providers: [
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    fixture = TestBed.createComponent(MosaiqueComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    fixture.destroy();
    http.verify({ ignoreCancelled: true });
  });

  // ------------------------------------------------------- quatre terminaux, quatre flux

  it('montre UNE TUILE PAR TERMINAL VIVANT — quatre, quand quatre travaillent', async () => {
    await setup([
      terminal({ workspaceId: 'w-1', workspaceName: 'web' }),
      terminal({ workspaceId: 'w-2', workspaceName: 'api' }),
      terminal({ workspaceId: 'w-3', workspaceName: 'batch' }),
      terminal({ workspaceId: 'w-4', workspaceName: 'docs' }),
    ]);

    expect(dom().querySelectorAll('.mosaique__tile').length).toBe(4);
    expect(dom().querySelectorAll('app-atelier-terminal').length).toBe(4);
  });

  it('montre le CONTENU RÉEL du flux, pas l\'aperçu du registre — c\'est toute la différence avec F-76', async () => {
    streams['w-1'] = [
      'event:attached\ndata:{"turnId":"t1","cursor":0,"startedAt":0}',
      'event:action\ndata:{"type":"bash","path":"npm run build"}',
      'event:output\ndata:{"output":"webpack compilé\\n"}',
    ];
    await setup([
      terminal({
        workspaceId: 'w-1',
        // Le registre porte un aperçu de F-76 : la tuile ne doit PAS s'en servir.
        previewLines: ['une ligne d\'aperçu venue du registre'],
        activity: 'RUNNING',
      }),
    ]);

    const tile = dom().querySelector('.mosaique__tile') as HTMLElement;

    expect(tile.querySelector('.terminal-command code')?.textContent?.trim()).toBe('npm run build');
    expect(tile.querySelector('.terminal-output')?.textContent).toContain('webpack compilé');
    expect(tile.textContent).not.toContain('une ligne d\'aperçu venue du registre');
  });

  it('ouvre un flux de LECTURE par tuile — l\'attache de F-84, jamais un envoi', async () => {
    await setup([terminal({ workspaceId: 'w-1' }), terminal({ workspaceId: 'w-2' })]);

    const urls = fetchSpy.calls.allArgs().map(([u]) => String(u));

    expect(urls).toContain('/api/workspaces/w-1/chat/attach?cursor=0');
    expect(urls).toContain('/api/workspaces/w-2/chat/attach?cursor=0');
  });

  // ------------------------------------- LE TEST QUI PROTÈGE LE PORTEFEUILLE DU PO

  it('NE CONSOMME AUCUNE PLACE ÉMETTRICE : on peut toujours ouvrir un quatrième terminal ailleurs', async () => {
    await setup([
      terminal({ workspaceId: 'w-1' }),
      terminal({ workspaceId: 'w-2' }),
      terminal({ workspaceId: 'w-3' }),
    ]);

    // Aucune prise de place, par aucun des deux chemins : ni `fetch`, ni `HttpClient`.
    const urls = fetchSpy.calls.allArgs().map(([u]) => String(u));
    expect(urls.some((url) => url.includes('/terminal/live'))).toBeFalse();
    http.expectNone((request) => request.url.includes('/terminal/live'));
    // Le registre n'a pas bougé : trois vivants sur quatre, la quatrième place reste libre.
    expect(component.liveCount()).toBe(3);
    expect(component.limit()).toBe(4);
  });

  it('ne lit le registre qu\'en LECTURE — un GET, jamais un POST', async () => {
    fixture.detectChanges();
    const request = http.expectOne(REGISTRY);

    expect(request.request.method).toBe('GET');
    request.flush(registry([]));
    await settle();
  });

  // ------------------------------------------- ce qui attend une décision se signale

  it('met EN TÊTE la tuile qui attend, lui pose l\'anneau, et compte l\'attente en toutes lettres', async () => {
    await setup([
      terminal({ workspaceId: 'w-1', workspaceName: 'web', openedAt: '2026-09-12T08:00:00Z' }),
      terminal({
        workspaceId: 'w-2',
        workspaceName: 'api',
        openedAt: '2026-09-12T09:00:00Z',
        activity: 'AWAITING_APPROVAL',
      }),
    ]);

    const tiles = Array.from(dom().querySelectorAll('.mosaique__tile'));

    expect(tiles[0].querySelector('.mosaique__project')?.textContent?.trim()).toBe('api');
    expect(tiles[0].classList).toContain('mosaique__tile--awaiting');
    expect(dom().querySelector('.mosaique__awaiting')?.textContent)
      .toContain('1 terminal attend votre autorisation');
  });

  it('accorde le compte quand plusieurs attendent', async () => {
    await setup([
      terminal({ workspaceId: 'w-1', activity: 'AWAITING_APPROVAL' }),
      terminal({ workspaceId: 'w-2', activity: 'AWAITING_APPROVAL' }),
    ]);

    expect(dom().querySelector('.mosaique__awaiting')?.textContent)
      .toContain('2 terminaux attendent votre autorisation');
  });

  it('n\'écrit rien quand rien n\'attend : il n\'y a rien à dire', async () => {
    await setup([terminal({ workspaceId: 'w-1' })]);

    expect(dom().querySelector('.mosaique__awaiting')).toBeNull();
  });

  // ------------------------------------------------ l'identité du client (SF-49-03)

  it('porte la couleur du client sur chaque tuile, et son nom ÉCRIT à côté', async () => {
    await setup([terminal({ workspaceId: 'w-1', workspaceName: 'web', hostName: 'CAGIP' })]);
    const tile = dom().querySelector('.mosaique__tile') as HTMLElement;

    expect(tile.style.borderLeftColor).not.toBe('');
    expect(tile.querySelector('.host-badge__mark')?.textContent?.trim()).toBe('CA');
    // La couleur ne porte JAMAIS seule l'information (charte §9).
    expect(tile.querySelector('.mosaique__host')?.textContent?.trim()).toBe('CAGIP');
  });

  it('un projet hébergé n\'emprunte aucun des dix tons : il n\'identifie aucune machine', async () => {
    await setup([terminal({ workspaceId: 'w-1', hostName: null, hostId: null })]);
    const tile = dom().querySelector('.mosaique__tile') as HTMLElement;

    expect(tile.querySelector('app-host-badge')).toBeNull();
    expect(tile.querySelector('.mosaique__host')?.textContent?.trim()).toBe('Hébergé');
  });

  // ------------------------------------------------------------ lecture seule stricte

  it('ne porte AUCUNE saisie : écrire reste un geste pris dans le terminal entier', async () => {
    streams['w-1'] = [
      'event:attached\ndata:{"turnId":"t1","cursor":0,"startedAt":0}',
      'event:confirm_request\ndata:{"toolUseId":"tu1","tool":"bash","detail":"rm -rf build"}',
    ];
    await setup([terminal({ workspaceId: 'w-1' })]);

    expect(dom().querySelector('input')).toBeNull();
    expect(dom().querySelector('form')).toBeNull();
    expect(dom().querySelector('.terminal-ask-allow')).toBeNull();
    // …mais l'attente, elle, est écrite : c'est l'exigence non négociable.
    expect(dom().textContent).toContain('Attend votre autorisation');
  });

  it('le fond de chaque tuile est EXACTEMENT celui du terminal — #141D33', async () => {
    await setup([terminal({ workspaceId: 'w-1' })]);
    const view = dom().querySelector('.terminal-view') as HTMLElement;

    expect(getComputedStyle(view).backgroundColor).toBe('rgb(20, 29, 51)');
  });

  it('offre un seul geste par tuile : entrer dans le terminal', async () => {
    await setup([terminal({ workspaceId: 'w-1' })]);
    const enter = dom().querySelector('.mosaique__enter') as HTMLAnchorElement;

    expect(enter).not.toBeNull();
    expect(enter.getAttribute('href')).toBe('/atelier/w-1');
  });

  // --------------------------------------------------------------------- densité

  it('donne aux flux la TRÈS GROSSE PART de la hauteur : au moins 85 %', async () => {
    // Une hauteur ferme, sinon la grille n'a rien à se partager dans le bac de test.
    const host = fixture.nativeElement as HTMLElement;
    host.style.height = '800px';
    host.style.display = 'block';
    await setup([
      terminal({ workspaceId: 'w-1' }),
      terminal({ workspaceId: 'w-2' }),
      terminal({ workspaceId: 'w-3' }),
      terminal({ workspaceId: 'w-4' }),
    ]);

    const page = dom().querySelector('.mosaique') as HTMLElement;
    const rows = new Set(
      Array.from(dom().querySelectorAll('.mosaique__flux')).map(
        (flux) => Math.round((flux as HTMLElement).getBoundingClientRect().top),
      ),
    );
    // La part de hauteur revenant aux flux : la somme d'UNE colonne (les lignes se superposent).
    const fluxHeight = Array.from(rows).reduce((total, top) => {
      const flux = Array.from(dom().querySelectorAll('.mosaique__flux')).find(
        (node) => Math.round((node as HTMLElement).getBoundingClientRect().top) === top,
      ) as HTMLElement;
      return total + flux.getBoundingClientRect().height;
    }, 0);
    const share = fluxHeight / page.getBoundingClientRect().height;

    // Mesuré : **90 %** sur 800 px de hauteur utile, quatre tuiles deux par deux. Le reste, ce
    // sont 24 px d'en-tête de page, 20 px par ligne de tuiles, et 4 px de gouttières.
    expect(share).toBeGreaterThan(0.85);
  });

  // ------------------------------------------------------------- pannes et cycle de vie

  it('rien n\'a jamais répondu : on le dit, et on propose de réessayer', async () => {
    fixture.detectChanges();
    http.expectOne(REGISTRY).error(new ProgressEvent('error'));
    fixture.detectChanges();

    expect(dom().textContent).toContain('L\'état des terminaux n\'a pas pu être lu');
    expect(dom().querySelectorAll('.mosaique__tile').length).toBe(0);
  });

  it('une panne APRÈS un succès ne vide pas la grille : on garde, et on dit depuis quand', async () => {
    await setup([terminal({ workspaceId: 'w-1' })]);

    component.refresh();
    http.expectOne(REGISTRY).error(new ProgressEvent('error'));
    fixture.detectChanges();

    expect(dom().querySelectorAll('.mosaique__tile').length).toBe(1);
    expect(dom().querySelector('.mosaique__updated--stale')).not.toBeNull();
  });

  it('aucun terminal ouvert : aucun flux ouvert, et l\'écran le dit', async () => {
    await setup([]);

    expect(dom().textContent).toContain('Aucun terminal ouvert');
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('un terminal qui disparaît du registre ferme sa lecture', async () => {
    await setup([terminal({ workspaceId: 'w-1' }), terminal({ workspaceId: 'w-2' })]);

    component.refresh();
    http.expectOne(REGISTRY).flush(registry([terminal({ workspaceId: 'w-1' })]));
    fixture.detectChanges();
    await settle();

    expect(dom().querySelectorAll('.mosaique__tile').length).toBe(1);
  });

  it('quitter l\'écran abandonne les quatre lectures — et rien de plus', async () => {
    await setup([
      terminal({ workspaceId: 'w-1' }),
      terminal({ workspaceId: 'w-2' }),
      terminal({ workspaceId: 'w-3' }),
      terminal({ workspaceId: 'w-4' }),
    ]);
    const closed: string[] = [];
    component['views'].forEach((view) => {
      const original = view.close.bind(view);
      spyOn(view, 'close').and.callFake(() => {
        closed.push(view.workspaceId);
        original();
      });
    });

    fixture.destroy();

    expect(closed.sort()).toEqual(['w-1', 'w-2', 'w-3', 'w-4']);
    // Aucune place n'est rendue : aucune n'avait été prise.
    http.expectNone((request) => request.url.includes('/terminal/live'));
  });

  it('dit ce que le plafond engage, avec le compte du registre', async () => {
    await setup([terminal({ workspaceId: 'w-1' }), terminal({ workspaceId: 'w-2' })]);

    expect(dom().querySelector('.mosaique__live')?.textContent).toContain('2 / 4');
  });
});
