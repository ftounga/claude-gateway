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

  it('purge à la clôture de mission avec la raison MISSION_CLOSED (SF-99-07)', () => {
    service.purgeRadar('h1', 'MISSION_CLOSED').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/purge').request.body)
      .toEqual({ reason: 'MISSION_CLOSED', confirm: true });
  });

  it("télécharge l'export Markdown en blob, avec ses en-têtes (SF-99-07)", () => {
    let fileName: string | null = null;
    service.exportRadar('h1').subscribe((response) => (fileName = response.headers.get('Content-Disposition')));
    const req = httpMock.expectOne('/api/radar/hosts/h1/export');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['# Radar']), { headers: { 'Content-Disposition': 'attachment; filename="radar-edenred.md"' } });
    expect(fileName).toContain('radar-edenred.md');
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

  it('lit le journal de diagnostic d\'un poste, avec le niveau minimum en paramètre (F-132)', () => {
    service.runnerDiag('h1').subscribe();
    const all = httpMock.expectOne('/api/runner-hosts/h1/diag');
    expect(all.request.method).toBe('GET');
    expect(all.request.params.get('level')).toBeNull();

    service.runnerDiag('h1', { level: 'WARN', limit: 50 }).subscribe();
    const filtered = httpMock.expectOne((r) => r.url === '/api/runner-hosts/h1/diag');
    expect(filtered.request.params.get('level')).toBe('WARN');
    expect(filtered.request.params.get('limit')).toBe('50');
  });

  it('passe un poste en DEBUG le temps d\'un diagnostic (F-132 / SF-132-05)', () => {
    service.setRunnerDiagDebug('h1', 10).subscribe();
    const req = httpMock.expectOne('/api/runner-hosts/h1/diag/level');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ minutes: 10 });

    service.setRunnerDiagDebug('h1').subscribe();
    expect(httpMock.expectOne('/api/runner-hosts/h1/diag/level').request.body).toEqual({});
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
