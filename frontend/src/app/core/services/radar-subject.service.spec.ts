import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RadarSubjectService } from './radar-subject.service';

/** La page d'un sujet, côté HTTP (F-103). */
describe('RadarSubjectService', () => {
  let service: RadarSubjectService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RadarSubjectService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lit la page du sujet sous le poste de l\'adresse', () => {
    service.subject('h1', 's1').subscribe();
    const req = httpMock.expectOne('/api/radar/hosts/h1/subjects/s1');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('lit ce que le Radar ne sait pas sur le sujet', () => {
    service.unknowns('h1', 's1').subscribe();
    const req = httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/unknowns');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('prépare la réponse au manager par un POST', () => {
    service.managerAnswer('h1', 's1').subscribe();
    const req = httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/manager-answer');
    expect(req.request.method).toBe('POST');
    req.flush({ text: 'OK', preparedAt: '2026-09-13T10:00:00Z', coverageIncomplete: false, unknownsCount: 0 });
  });

  it('sépare, ajoute et retire un alias, lit et annule le journal (SF-99-06)', () => {
    service.split('h1', 's1', { name: 'Okta', evidenceIds: ['p1'], commitmentIds: [] }).subscribe();
    const split = httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/split');
    expect(split.request.method).toBe('POST');
    expect(split.request.body).toEqual({ name: 'Okta', evidenceIds: ['p1'], commitmentIds: [] });
    split.flush({});

    service.addAlias('h1', 's1', 'Chantier Okta').subscribe();
    const add = httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/aliases');
    expect(add.request.method).toBe('POST');
    expect(add.request.body).toEqual({ alias: 'Chantier Okta' });
    add.flush({});

    service.removeAlias('h1', 's1', 'a1').subscribe();
    const remove = httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/aliases/a1');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);

    service.corrections('h1', 's1').subscribe();
    const journal = httpMock.expectOne((r) => r.url === '/api/radar/hosts/h1/corrections');
    expect(journal.request.params.get('subjectId')).toBe('s1');
    journal.flush([]);

    service.undo('h1', 'c1').subscribe();
    const undo = httpMock.expectOne('/api/radar/hosts/h1/corrections/c1/undo');
    expect(undo.request.method).toBe('POST');
    undo.flush({});
  });
});
