import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { LiveTerminalService } from './live-terminal.service';
import { LiveTerminals } from '../models/atelier.models';

/**
 * Le registre des terminaux vivants, vu de l'écran (F-70 / SF-70-01).
 *
 * <p>Trois choses s'y vérifient, et la troisième est la plus importante : que le terminal prenne sa
 * place, que le <b>refus du plafond</b> bloque, et qu'<b>aucune autre panne</b> ne bloque. Une
 * gateway indisponible ne doit pas interdire de travailler — sinon le garde-fou de dépense devient
 * un interrupteur d'arrêt.</p>
 */
describe('LiveTerminalService', () => {
  let service: LiveTerminalService;
  let http: HttpTestingController;
  let fetchSpy: jasmine.Spy;

  const workspaceId = 'w-1';

  const registry: LiveTerminals = {
    limit: 4,
    live: 1,
    terminals: [
      {
        workspaceId,
        workspaceName: 'web',
        hostId: 'h-1',
        hostName: 'CAGIP',
        openedAt: '2026-09-12T09:00:00Z',
      },
    ],
  };

  beforeEach(() => {
    sessionStorage.clear();
    // La libération part en `fetch` (keepalive) : on l'observe plutôt que de la laisser sortir.
    fetchSpy = spyOn(window, 'fetch').and.returnValue(Promise.resolve(new Response()));
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), LiveTerminalService],
    });
    service = TestBed.inject(LiveTerminalService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    service.stop();
    http.verify({ ignoreCancelled: true });
    sessionStorage.clear();
  });

  it('prend une place et allume le signe de vie', () => {
    service.start(workspaceId);

    const request = http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body.sessionId).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
    request.flush(registry);

    expect(service.live()).toBe(true);
    expect(service.limitReached()).toBe(false);
    expect(service.liveCount()).toBe(1);
    expect(service.limit()).toBe(4);
  });

  it("n'emporte aucun identifiant d'utilisateur : l'isolation vient du jeton", () => {
    service.start(workspaceId);
    const request = http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`);
    expect(Object.keys(request.request.body)).toEqual(['sessionId']);
    request.flush(registry);
  });

  it('garde le même identifiant d’onglet entre deux battements', () => {
    service.start(workspaceId);
    const first = http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`);
    const sessionId = first.request.body.sessionId;
    first.flush(registry);

    service.retry();
    const second = http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`);
    expect(second.request.body.sessionId).toBe(sessionId);
    second.flush(registry);
  });

  it('bloque sur un 409 — le refus explicite du plafond — et relit le registre', () => {
    service.start(workspaceId);
    http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`).flush(
      { error: 'terminal_limit_reached', message: 'Quatre terminaux actifs au maximum.' },
      { status: 409, statusText: 'Conflict' },
    );

    expect(service.limitReached()).toBe(true);
    expect(service.live()).toBe(false);

    // Le refus doit pouvoir NOMMER les quatre : sans cela, « fermez-en un » n'est pas actionnable.
    const refresh = http.expectOne('/api/terminals/live');
    expect(refresh.request.method).toBe('GET');
    refresh.flush({ limit: 4, live: 4, terminals: registry.terminals });
    expect(service.registry()?.terminals.length).toBe(1);
  });

  it("ne bloque rien sur une panne réseau : seul le plafond refuse", () => {
    service.start(workspaceId);
    http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`).flush(
      { error: 'internal_error', message: 'oups' },
      { status: 500, statusText: 'Server Error' },
    );

    expect(service.limitReached()).toBe(false);
    expect(service.live()).toBe(false);
  });

  it('libère la place en quittant', () => {
    service.start(workspaceId);
    http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`).flush(registry);

    service.stop();

    expect(fetchSpy).toHaveBeenCalled();
    const [url, init] = fetchSpy.calls.mostRecent().args as [string, RequestInit];
    expect(url).toContain(`/api/workspaces/${workspaceId}/terminal/live?sessionId=`);
    expect(init.method).toBe('DELETE');
    // `keepalive` : c'est ce qui permet à la libération d'arriver alors que l'onglet se ferme.
    expect(init.keepalive).toBe(true);
    expect(service.live()).toBe(false);
  });

  it('déplace la place quand le même onglet change de projet', () => {
    service.start(workspaceId);
    http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`).flush(registry);

    service.start('w-2');
    const moved = http.expectOne('/api/workspaces/w-2/terminal/live');
    moved.flush({ ...registry, terminals: [] });
    expect(service.live()).toBe(true);
  });

  // ---------------------------------------- l'aperçu vivant (F-76 / SF-76-02)

  describe('l’aperçu', () => {

    const running = {
      activity: 'RUNNING' as const,
      activityDetail: 'npm test',
      previewLines: ['$ npm test', 'PASS'],
    };

    function claimed(): void {
      service.start(workspaceId);
      http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`).flush(registry);
    }

    it('part IMMÉDIATEMENT quand l’activité change', () => {
      // C'est l'exigence non négociable : « attend une autorisation » doit se voir en quelques
      // secondes, pas au battement suivant.
      claimed();

      service.report(running);

      const request = http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`);
      expect(request.request.body.activity).toBe('RUNNING');
      expect(request.request.body.activityDetail).toBe('npm test');
      expect(request.request.body.previewLines).toEqual(['$ npm test', 'PASS']);
      request.flush(registry);
    });

    it('ne renvoie pas deux fois le même relevé', () => {
      claimed();
      service.report(running);
      http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`).flush(registry);

      service.report({ ...running, previewLines: ['$ npm test', 'PASS'] });

      http.expectNone(`/api/workspaces/${workspaceId}/terminal/live`);
    });

    it('apaise le simple défilement de lignes', () => {
      // À activité constante, rien ne presse : un envoi toutes les cinq secondes suffit à un
      // aperçu qu'on regarde du coin de l'œil.
      jasmine.clock().install();
      try {
        claimed();
        service.report(running);
        http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`).flush(registry);

        service.report({ ...running, previewLines: ['$ npm test', 'PASS', 'PASS bis'] });
        http.expectNone(`/api/workspaces/${workspaceId}/terminal/live`);

        jasmine.clock().tick(5_000);
        const deferred = http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`);
        expect(deferred.request.body.previewLines.length).toBe(3);
        deferred.flush(registry);
      } finally {
        jasmine.clock().uninstall();
      }
    });

    it('ne relève rien tant qu’aucune place n’est tenue', () => {
      // Il n'y a pas de fiche à décorer : le relevé n'a nulle part où aller.
      service.report(running);

      http.expectNone(`/api/workspaces/${workspaceId}/terminal/live`);
    });

    it('n’abandonne aucune minuterie d’aperçu derrière lui', () => {
      jasmine.clock().install();
      try {
        claimed();
        service.report(running);
        http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`).flush(registry);
        service.report({ ...running, previewLines: ['autre'] });

        service.stop();
        jasmine.clock().tick(30_000);

        http.expectNone(`/api/workspaces/${workspaceId}/terminal/live`);
      } finally {
        jasmine.clock().uninstall();
      }
    });

    it('oublie l’aperçu du projet précédent quand l’onglet change de projet', () => {
      // Laisser l'aperçu en place afficherait le `npm test` d'un autre projet sur la carte de
      // celui-ci.
      claimed();
      service.report(running);
      http.expectOne(`/api/workspaces/${workspaceId}/terminal/live`).flush(registry);

      service.start('w-2');

      const request = http.expectOne('/api/workspaces/w-2/terminal/live');
      expect(request.request.body.activity).toBeUndefined();
      request.flush(registry);
    });
  });
});
