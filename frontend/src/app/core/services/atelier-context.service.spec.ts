import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { AtelierContextService } from './atelier-context.service';
import { ThreadContextSummary } from '../../atelier/terminal/slash-panel-commands';

/**
 * L'état mémoire du fil (F-165 / SF-165-03) : une lecture, un GET, aucun tour. On vérifie l'URL isolée par
 * projet et le mapping de la réponse.
 */
describe('AtelierContextService (F-165 / SF-165-03)', () => {
  let service: AtelierContextService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AtelierContextService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lit l\'état mémoire du fil par un GET sur le projet', () => {
    const payload: ThreadContextSummary = {
      contextTokens: 60000, contextPages: 120, liveTurns: 3, foldedTurns: 5,
      hasAnchoredSummary: true, compactionEnabled: true, triggerTokens: 120000,
      triggerPages: 240, fillPercent: 50, keepRecentTurns: 6, recallSemantic: true,
    };

    let received: ThreadContextSummary | undefined;
    service.contextSummary('w1').subscribe((summary) => (received = summary));

    const req = http.expectOne('/api/workspaces/w1/chat/context-summary');
    expect(req.request.method).toBe('GET');
    req.flush(payload);

    expect(received).toEqual(payload);
  });
});
