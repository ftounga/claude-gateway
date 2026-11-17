import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AppRequestService } from './app-request.service';

/** F-269 — le filtre par type et la saisie directe d'une commande pour Claude Code. */
describe('AppRequestService (F-269)', () => {
  let service: AppRequestService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AppRequestService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('list passe le statut et le type', () => {
    service.list('EN_ATTENTE', 'COMMANDE').subscribe();
    const req = http.expectOne((r) => r.url === '/api/admin/app-requests');
    expect(req.request.params.get('status')).toBe('EN_ATTENTE');
    expect(req.request.params.get('kind')).toBe('COMMANDE');
    req.flush([]);
  });

  it('list sans filtre n’envoie aucun paramètre', () => {
    service.list(null).subscribe();
    const req = http.expectOne((r) => r.url === '/api/admin/app-requests');
    expect(req.request.params.keys()).toEqual([]);
    req.flush([]);
  });

  it('createCommand poste le texte tel quel', () => {
    service.createCommand('  lance les bancs\n').subscribe();
    const req = http.expectOne('/api/admin/app-requests/commands');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ text: '  lance les bancs\n' });
    req.flush({});
  });
});
