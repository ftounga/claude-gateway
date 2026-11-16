import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { GovernanceService } from './governance.service';

/**
 * L'accès au catalogue et à la carte (F-51 / SF-51-05, F-92 / SF-92-03).
 *
 * <p>Ce que ces tests protègent : <b>aucune URL ne porte d'identifiant d'utilisateur</b>.
 * L'isolation est entièrement portée par la gateway à partir du JWT, et une URL qui nommerait un
 * compte donnerait l'illusion qu'elle décide de quelque chose.</p>
 */
describe('GovernanceService', () => {
  let service: GovernanceService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GovernanceService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lit la carte d’un poste par sa référence', () => {
    service.getMap('h1').subscribe();

    const request = http.expectOne('/api/governance/hosts/h1/map');
    expect(request.request.method).toBe('GET');
    request.flush(null);
  });

  it('lit le plan de la carte et la fiche d’une ressource (F-173), sans identifiant d’utilisateur', () => {
    service.hostMapGraph('h1').subscribe();
    service.hostMapEntity('h1', '3f9a0c1d2e4b5a69').subscribe();

    const graph = http.expectOne('/api/governance/hosts/h1/map/graph');
    expect(graph.request.method).toBe('GET');
    graph.flush(null);
    http.expectOne('/api/governance/hosts/h1/map/entities/3f9a0c1d2e4b5a69').flush(null);
  });

  it('lit les propositions de consolidation de la carte (F-174 / SF-174-06, écran F-173)', () => {
    service.hostMapConsolidation('h1').subscribe();
    http.expectOne('/api/governance/hosts/h1/map/consolidation').flush(null);
  });

  it('lit la carte du poste « Hébergé » par son mot réservé, jamais par un identifiant', () => {
    service.getMap('hosted').subscribe();

    http.expectOne('/api/governance/hosts/hosted/map').flush(null);
  });

  it('lit un fichier de carte en passant son chemin en paramètre de requête', () => {
    service.readMapFile('h1', 'acces.md').subscribe();

    const request = http.expectOne(
      (candidate) => candidate.url === '/api/governance/hosts/h1/map/file',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('path')).toBe('acces.md');
    request.flush(null);
  });

  it('F-266 : liste, retire et recopie les règles de tous mes postes, sans identifiant d’utilisateur', () => {
    service.listAccountRules().subscribe();
    service.deleteAccountRule('r-1').subscribe();
    service.promoteHostRule('h1', 'Style des preuves').subscribe();

    const list = http.expectOne('/api/governance/account-rules');
    expect(list.request.method).toBe('GET');
    list.flush([]);
    const removal = http.expectOne('/api/governance/account-rules/r-1');
    expect(removal.request.method).toBe('DELETE');
    removal.flush(null);
    const promote = http.expectOne('/api/governance/hosts/h1/account-rules');
    expect(promote.request.method).toBe('POST');
    expect(promote.request.body).toEqual({ title: 'Style des preuves' });
    promote.flush({ id: 'r-2', title: 'Style des preuves', content: 'x' });
  });
});
