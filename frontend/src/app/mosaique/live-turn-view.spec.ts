import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NgZone } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { AtelierService } from '../core/services/atelier.service';
import { LiveTurnView } from './live-turn-view';

/**
 * **La place lectrice** (F-83 / SF-83-02).
 *
 * <p>Ce qu'on vérifie ici est ce qui distingue F-83 de F-76 : la tuile reçoit le <b>contenu réel</b>
 * du flux — commandes, sorties, commentaire —, pas un résumé. Et elle le reçoit du flux de
 * rebranchement de F-84, qui n'ouvre aucun tour et ne prend aucune place.</p>
 */
describe('LiveTurnView (F-83 / SF-83-02)', () => {
  let atelier: AtelierService;
  let zone: NgZone;

  /** Flux SSE factice : `fetch` renvoie les événements fournis, puis se clôt. Aucun réseau. */
  function fakeSse(events: string[]): jasmine.Spy {
    return spyOn(window, 'fetch').and.callFake(() => {
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

  /**
   * Laisse le flux se dérouler. La lecture vit dans des **micro-tâches** — pas dans une minuterie :
   * on les vide, ce qui laisse l'horloge de Jasmine libre pour les rebranchements différés.
   */
  async function settle(): Promise<void> {
    for (let i = 0; i < 50; i += 1) {
      await Promise.resolve();
    }
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    atelier = TestBed.inject(AtelierService);
    zone = TestBed.inject(NgZone);
    jasmine.clock().install();
  });

  afterEach(() => {
    jasmine.clock().uninstall();
  });

  it('branche le rebranchement de F-84 — jamais la prise de place de F-70', async () => {
    const fetchSpy = fakeSse(['event:idle\ndata:{"live":false}']);
    const view = new LiveTurnView('w-1', atelier, zone);

    view.open();
    await settle();

    expect(fetchSpy).toHaveBeenCalled();
    const url = String(fetchSpy.calls.mostRecent().args[0]);
    expect(url).toBe('/api/workspaces/w-1/chat/attach?cursor=0');
    // LA PLACE ÉMETTRICE N'EST JAMAIS TOUCHÉE : c'est ce qui protège le portefeuille du PO.
    expect(fetchSpy.calls.allArgs().some(([u]) => String(u).includes('/terminal/live'))).toBeFalse();
    view.close();
  });

  it('montre le CONTENU RÉEL du flux : les commandes, leur sortie et le commentaire', async () => {
    fakeSse([
      'event:attached\ndata:{"turnId":"t1","cursor":0,"startedAt":0}',
      'event:action\ndata:{"type":"bash","path":"npm test"}',
      'event:output\ndata:{"output":"PASS app.spec.ts\\n"}',
      'event:text\ndata:{"text":"Les tests passent."}',
    ]);
    const view = new LiveTurnView('w-1', atelier, zone);

    view.open();
    await settle();
    const stream = view.stream();

    expect(stream).not.toBeNull();
    expect(stream!.blocks.length).toBe(1);
    expect(stream!.blocks[0].command).toBe('npm test');
    expect(stream!.blocks[0].output).toBe('PASS app.spec.ts\n');
    expect(stream!.text).toBe('Les tests passent.');
    view.close();
  });

  it('relaie tokens et plan : la ligne vivante d\'une tuile dit la même chose qu\'ailleurs', async () => {
    fakeSse([
      'event:attached\ndata:{"turnId":"t1","cursor":0,"startedAt":0}',
      'event:progress\ndata:{"tokens":1234}',
      'event:plan\ndata:{"steps":[{"title":"Compiler","status":"done"}]}',
    ]);
    const view = new LiveTurnView('w-1', atelier, zone);

    view.open();
    await settle();

    expect(view.stream()?.tokens).toBe(1234);
    expect(view.stream()?.plan?.[0].title).toBe('Compiler');
    view.close();
  });

  it('signale une autorisation attendue, et la retire quand elle est tranchée', async () => {
    fakeSse([
      'event:attached\ndata:{"turnId":"t1","cursor":0,"startedAt":0}',
      'event:confirm_request\ndata:{"toolUseId":"tu1","tool":"bash","detail":"rm -rf build"}',
    ]);
    const view = new LiveTurnView('w-1', atelier, zone);

    view.open();
    await settle();

    expect(view.pending()?.detail).toBe('rm -rf build');
    // Répondre n'est PAS un geste de la tuile : l'invite n'y porte aucun bouton (SF-83-01).
    expect(view.pending()?.answering).toBeFalse();
    view.close();
  });

  it('rien ne tourne : la tuile se met AU REPOS, et se rebranche plus tard', async () => {
    const fetchSpy = fakeSse(['event:idle\ndata:{"live":false}']);
    const view = new LiveTurnView('w-1', atelier, zone);

    view.open();
    await settle();

    expect(view.stream()).toBeNull();
    const first = fetchSpy.calls.count();

    jasmine.clock().tick(5_000);

    expect(fetchSpy.calls.count()).toBe(first + 1);
    view.close();
  });

  it('un tour fini n\'efface pas la tuile : elle attend le suivant', async () => {
    fakeSse([
      'event:attached\ndata:{"turnId":"t1","cursor":0,"startedAt":0}',
      'event:action\ndata:{"type":"bash","path":"npm test"}',
      'event:done\ndata:{"reply":"Terminé.","actions":[],"messageId":"m1"}',
    ]);
    const view = new LiveTurnView('w-1', atelier, zone);

    view.open();
    await settle();

    expect(view.stream()).toBeNull();
    expect(view.pending()).toBeNull();
    view.close();
  });

  it('dit le trou plutôt que de le maquiller quand le rejeu est tronqué', async () => {
    fakeSse([
      // L'ordre de SF-84-02 : on se branche, on annonce le trou, puis on rejoue.
      'event:attached\ndata:{"turnId":"t1","cursor":40,"startedAt":0}',
      'event:truncated\ndata:{"fromSeq":40,"droppedThrough":39}',
      'event:action\ndata:{"type":"bash","path":"npm test"}',
    ]);
    const view = new LiveTurnView('w-1', atelier, zone);

    view.open();
    await settle();

    expect(view.truncated()).toBeTrue();
    expect(view.stream()?.blocks.length).toBe(1);
    view.close();
  });

  it('fermer abandonne la lecture et n\'en rouvre plus aucune', async () => {
    const fetchSpy = fakeSse(['event:idle\ndata:{"live":false}']);
    const view = new LiveTurnView('w-1', atelier, zone);

    view.open();
    await settle();
    view.close();
    const calls = fetchSpy.calls.count();

    jasmine.clock().tick(30_000);

    expect(fetchSpy.calls.count()).toBe(calls);
  });
});
