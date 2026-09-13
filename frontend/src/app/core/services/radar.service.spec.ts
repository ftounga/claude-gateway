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
