import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RadarService } from './radar.service';

/** Le Radar d'un client, côté HTTP (F-102). */
describe('RadarService', () => {
  let service: RadarService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(RadarService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lit le résumé, lance et annule une synchro', () => {
    service.brief('h1').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/brief').request.method).toBe('GET');

    service.syncNow('h1').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/syncs').request.method).toBe('POST');

    service.cancelSync('h1', 's1').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/syncs/s1/cancel').request.method).toBe('POST');
  });

  it('lit les colonnes et envoie les gestes aux corrections souveraines', () => {
    service.board('h1').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/board').request.method).toBe('GET');

    service.correctCommitment('h1', 'c1', 'DONE').subscribe();
    const done = httpMock.expectOne('/api/radar/hosts/h1/commitments/c1/corrections');
    expect(done.request.method).toBe('POST');
    expect(done.request.body).toEqual({ action: 'DONE' });

    service.correctCommitment('h1', 'c1', 'POSTPONE', '2026-09-22').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/commitments/c1/corrections').request.body)
      .toEqual({ action: 'POSTPONE', dueDate: '2026-09-22' });

    service.closeSubject('h1', 's1').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/close').request.method).toBe('POST');
    service.confirmClosure('h1', 's1').subscribe();
    httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/close-proposal/confirm');
    service.rejectClosure('h1', 's1').subscribe();
    httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/close-proposal/reject');
    service.dismissWake('h1', 's1').subscribe();
    httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/wake/dismiss');
    service.setSubjectState('h1', 's1', 'ADVANCING').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/subjects/s1/corrections').request.body)
      .toEqual({ action: 'SET_STATE', state: 'ADVANCING' });
    service.undo('h1', 'k1').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/corrections/k1/undo').request.method).toBe('POST');
  });

  it('pose, lit et retire une règle de fil', () => {
    service.addThreadRule('h1', '19:x', 'IGNORE', 'Afterwork').subscribe();
    const post = httpMock.expectOne('/api/radar/hosts/h1/thread-rules');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ conversationRef: '19:x', rule: 'IGNORE', label: 'Afterwork' });

    service.threadRules('h1').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/thread-rules').request.method).toBe('GET');

    service.removeThreadRule('h1', 'r1').subscribe();
    expect(httpMock.expectOne('/api/radar/hosts/h1/thread-rules/r1').request.method).toBe('DELETE');
  });
});
