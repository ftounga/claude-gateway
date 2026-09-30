import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { AtelierCostService } from './atelier-cost.service';
import { ThreadCostSummary } from '../../atelier/terminal/slash-panel-commands';

/**
 * L'économie du fil (F-165 / SF-165-02) : une lecture, un GET, aucun tour. On vérifie l'URL isolée par
 * projet et le mapping de la réponse.
 */
describe('AtelierCostService (F-165 / SF-165-02)', () => {
  let service: AtelierCostService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AtelierCostService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lit l\'économie du fil par un GET sur le projet', () => {
    const payload: ThreadCostSummary = {
      currency: 'EUR', cumulativeEur: 0.46, lastTurnEur: 0.46, turnCount: 1,
      breakdown: {
        writeEur: 0.08, writePercent: 49, readEur: 0.04, readPercent: 24,
        outputEur: 0.05, outputPercent: 27,
      },
      hotCachePercent: 90, contextTokens: 100000, contextPages: 200,
      liveTurns: 3, foldedTurns: 5, trendEur: [0.46],
    };

    let received: ThreadCostSummary | undefined;
    service.costSummary('w1').subscribe((summary) => (received = summary));

    const req = http.expectOne('/api/workspaces/w1/chat/cost-summary');
    expect(req.request.method).toBe('GET');
    req.flush(payload);

    expect(received).toEqual(payload);
  });
});
