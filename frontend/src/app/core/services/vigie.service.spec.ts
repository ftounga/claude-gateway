import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { VigieRadarCounts } from '../models/vigie.models';
import { VigieService } from './vigie.service';

/** La Vigie et les espaces d'un client, côté HTTP (F-106 / SF-106-02). */
describe('VigieService', () => {
  let service: VigieService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(VigieService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lit, active et retire les espaces', () => {
    service.hostSpaces().subscribe();
    expect(httpMock.expectOne('/api/runner-hosts/spaces').request.method).toBe('GET');

    service.activate('h1', 'VIGIE').subscribe();
    expect(httpMock.expectOne('/api/runner-hosts/h1/spaces/VIGIE').request.method).toBe('PUT');

    service.remove('h1', 'VIGIE').subscribe();
    expect(httpMock.expectOne('/api/runner-hosts/h1/spaces/VIGIE').request.method).toBe('DELETE');
  });

  it('purge le Radar avec la raison VIGIE_REMOVED et une confirmation explicite', () => {
    service.purgeRadar('h1').subscribe();
    const req = httpMock.expectOne('/api/radar/hosts/h1/purge');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'VIGIE_REMOVED', confirm: true });
  });

  it("lit l'annuaire du client", () => {
    service.people('h1').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/people').request.method).toBe('GET');
  });

  it('compte relances dues, sujets bloqués et dernière synchro', () => {
    let counts: VigieRadarCounts | undefined;
    service.radarCounts('h1').subscribe((c) => (counts = c));

    const followUps = httpMock.expectOne((r) => r.url === '/api/radar/hosts/h1/commitments');
    expect(followUps.request.params.get('followUpDue')).toBe('true');
    followUps.flush([{ id: 'c1', followUpDue: true }, { id: 'c2', followUpDue: false }]);
    const blocked = httpMock.expectOne((r) => r.url === '/api/radar/hosts/h1/subjects');
    expect(blocked.request.params.get('state')).toBe('BLOCKED');
    blocked.flush([{ id: 's1', name: 'MFA', state: 'BLOCKED' }]);
    httpMock.expectOne('/api/radar/hosts/h1/syncs').flush([
      { id: 'y1', status: 'SUCCEEDED', startedAt: '2026-09-11T20:00:00Z', finishedAt: null },
      { id: 'y2', status: 'PARTIAL', startedAt: '2026-09-12T20:00:00Z', finishedAt: null },
    ]);

    expect(counts).toEqual({
      followUpsDue: 1,
      blockedSubjects: 1,
      lastSync: { id: 'y2', status: 'PARTIAL', startedAt: '2026-09-12T20:00:00Z', finishedAt: null },
    });
  });

  it('un Radar illisible compte zéro, sans erreur', () => {
    let counts: VigieRadarCounts | undefined;
    let failed = false;
    service.radarCounts('h1').subscribe({ next: (c) => (counts = c), error: () => (failed = true) });

    httpMock.expectOne((r) => r.url === '/api/radar/hosts/h1/commitments')
      .flush({ error: 'host_not_in_space' }, { status: 409, statusText: 'Conflict' });
    httpMock.expectOne((r) => r.url === '/api/radar/hosts/h1/subjects')
      .flush(null, { status: 403, statusText: 'Forbidden' });
    httpMock.expectOne('/api/radar/hosts/h1/syncs').error(new ProgressEvent('offline'));

    expect(failed).toBeFalse();
    expect(counts).toEqual({ followUpsDue: 0, blockedSubjects: 0, lastSync: null });
  });
});
