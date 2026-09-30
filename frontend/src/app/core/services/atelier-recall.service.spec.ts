import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { AtelierRecallService } from './atelier-recall.service';
import { ThreadRecallResult } from '../../atelier/terminal/slash-panel-commands';

/**
 * Le rappel (F-165 / SF-165-06) : une recherche, un GET, aucun tour. On vérifie l'URL isolée par projet,
 * le paramètre de terme et le mapping.
 */
describe('AtelierRecallService (F-165 / SF-165-06)', () => {
  let service: AtelierRecallService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AtelierRecallService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('cherche par un GET sur le projet avec le terme en paramètre', () => {
    const payload: ThreadRecallResult = {
      query: 'vpc', semantic: false,
      extracts: [{ role: 'user', excerpt: 'Comment configurer le VPC ?', createdAt: '2026-09-30T10:00:00Z' }],
    };

    let received: ThreadRecallResult | undefined;
    service.recall('w1', 'vpc').subscribe((r) => (received = r));

    const req = http.expectOne((r) => r.url === '/api/workspaces/w1/chat/recall');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('vpc');
    req.flush(payload);

    expect(received).toEqual(payload);
  });
});
