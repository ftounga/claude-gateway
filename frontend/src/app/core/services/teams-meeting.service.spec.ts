import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { TeamsMeetingService } from './teams-meeting.service';

/** Les API Réunions de la Vigie, côté HTTP (F-128 / SF-128-01). */
describe('TeamsMeetingService', () => {
  let service: TeamsMeetingService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(TeamsMeetingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('liste et lit une réunion du poste', () => {
    service.list('h1').subscribe();
    expect(httpMock.expectOne('/api/vigie/hosts/h1/meetings').request.method).toBe('GET');

    service.get('h1', 'm1').subscribe();
    expect(httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1').request.method).toBe('GET');
  });

  it('crée une réunion (Rejoindre & capturer) avec le corps attendu', () => {
    const body = { meetingUrl: 'https://teams.microsoft.com/x', consentAcknowledged: true, retentionDays: 30 };
    service.create('h1', body).subscribe();
    const req = httpMock.expectOne('/api/vigie/hosts/h1/meetings');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
  });

  it('arrête, met en pause et reprend', () => {
    service.stop('h1', 'm1').subscribe();
    expect(httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/stop').request.method).toBe('POST');

    service.pause('h1', 'm1').subscribe();
    expect(httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/pause').request.method).toBe('POST');

    service.resume('h1', 'm1').subscribe();
    expect(httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/resume').request.method).toBe('POST');
  });

  it('charge l\'audio en blob (SF-128-10)', () => {
    service.audioBlob('h1', 'm1').subscribe();
    const req = httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/audio');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
  });

  it('liste les identifiants d\'images et déballe imageIds', () => {
    let ids: string[] | undefined;
    service.imageIds('h1', 'm1').subscribe((v) => (ids = v));
    const req = httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/images');
    expect(req.request.method).toBe('GET');
    req.flush({ imageIds: ['a', 'b'] });
    expect(ids).toEqual(['a', 'b']);
  });

  it('charge une image en blob', () => {
    service.imageBlob('h1', 'm1', 'img1').subscribe();
    const req = httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/images/img1');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
  });

  it('déclenche la transcription et lit le transcript (SF-128-04)', () => {
    service.transcribe('h1', 'm1').subscribe();
    expect(httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/transcribe').request.method).toBe('POST');

    service.transcript('h1', 'm1').subscribe();
    const req = httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/transcript');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('text');
  });

  it('analyse et interroge l\'agent (SF-128-05)', () => {
    service.insights('h1', 'm1').subscribe();
    expect(httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/insights').request.method).toBe('POST');

    service.ask('h1', 'm1', 'Qui décide ?').subscribe();
    const req = httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/ask');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ question: 'Qui décide ?' });
  });

  it('range la réunion dans la carte du poste (SF-128-11)', () => {
    service.promoteToCard('h1', 'm1').subscribe();
    const req = httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/promote-to-card');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
  });

  it('pousse les actions vers le Radar avec le sujet et les actions (SF-128-06)', () => {
    service.pushActionsToRadar('h1', 'm1', ['Envoyer le CR'], 's1').subscribe();
    const req = httpMock.expectOne('/api/vigie/hosts/h1/meetings/m1/actions-to-radar');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ subjectId: 's1', actions: ['Envoyer le CR'] });
  });
});
