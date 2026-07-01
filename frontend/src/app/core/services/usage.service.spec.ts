import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { UsageService } from './usage.service';
import { UsageView } from '../models/usage.models';

describe('UsageService', () => {
  let service: UsageService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [UsageService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UsageService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the current usage from /api/usage', () => {
    const usage: UsageView = {
      usedTokens: 4200,
      quotaTokens: 200000,
      remainingTokens: 195800,
      periodStart: '2026-07-01',
      periodEnd: '2026-08-01',
    };
    let received: UsageView | undefined;
    service.getUsage().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/usage');
    expect(req.request.method).toBe('GET');
    // Le client n'envoie aucun identifiant utilisateur (isolation garantie côté backend via le JWT).
    expect(req.request.params.keys().length).toBe(0);
    req.flush(usage);
    expect(received).toEqual(usage);
  });
});
