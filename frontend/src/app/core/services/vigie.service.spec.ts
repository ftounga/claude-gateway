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

  const brief = (running: unknown, lastSync: unknown) => ({
    generatedAt: '2026-09-13T06:00:00Z', since: '2026-09-12T06:00:00Z', sentences: [],
    counts: { toDoByMe: 3, followUpsDue: 1, introductions: 0, subjectsFollowed: 4, blockedSubjects: 1, toHandle: 5 },
    running, lastSync, coverageComplete: false, coverageWarning: null, coverageLines: [],
  });

  it('compte relances dues, sujets bloqués, à traiter et la synchro, d\'une seule lecture du résumé (F-102)', () => {
    let counts: VigieRadarCounts | undefined;
    service.radarCounts('h1').subscribe((c) => (counts = c));

    httpMock.expectOne('/api/radar/hosts/h1/brief').flush(brief(null,
      { id: 'y2', status: 'PARTIAL', startedAt: '2026-09-12T20:00:00Z', finishedAt: '2026-09-12T20:30:00Z',
        trigger: 'SCHEDULED', scheduledFor: null, summary: null }));

    expect(counts).toEqual({
      followUpsDue: 1,
      blockedSubjects: 1,
      toHandle: 5,
      lastSync: { id: 'y2', status: 'PARTIAL', startedAt: '2026-09-12T20:00:00Z', finishedAt: '2026-09-12T20:30:00Z' },
    });
  });

  it('une synchro en cours passe avant la dernière terminée', () => {
    let counts: VigieRadarCounts | undefined;
    service.radarCounts('h1').subscribe((c) => (counts = c));

    httpMock.expectOne('/api/radar/hosts/h1/brief').flush(brief(
      { id: 'y3', status: 'RUNNING', startedAt: '2026-09-13T07:00:00Z', finishedAt: null },
      { id: 'y2', status: 'SUCCEEDED', startedAt: '2026-09-12T20:00:00Z', finishedAt: '2026-09-12T20:30:00Z' }));

    expect(counts?.lastSync?.id).toBe('y3');
    expect(counts?.lastSync?.status).toBe('RUNNING');
  });

  it('un Radar illisible compte zéro, sans erreur', () => {
    let counts: VigieRadarCounts | undefined;
    let failed = false;
    service.radarCounts('h1').subscribe({ next: (c) => (counts = c), error: () => (failed = true) });

    httpMock.expectOne('/api/radar/hosts/h1/brief')
      .flush({ error: 'host_not_in_space' }, { status: 409, statusText: 'Conflict' });

    expect(failed).toBeFalse();
    expect(counts).toEqual({ followUpsDue: 0, blockedSubjects: 0, toHandle: 0, lastSync: null });
  });
});
