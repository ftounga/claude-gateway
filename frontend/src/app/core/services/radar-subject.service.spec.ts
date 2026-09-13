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
});
