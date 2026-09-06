import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { QuotaAlertService } from './quota-alert.service';
import { QuotaAlertView } from '../models/quota-alert.models';

describe('QuotaAlertService', () => {
  let service: QuotaAlertService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [QuotaAlertService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(QuotaAlertService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the current quota alert from /api/usage/alert', () => {
    const alert: QuotaAlertView = {
      raised: true,
      usedTokens: 850000,
      quotaTokens: 1000000,
      remainingTokens: 150000,
      usedPercent: 85,
      thresholdPercent: 80,
      periodEnd: '2026-08-01',
      topUp: { code: 'STANDARD', label: 'Recharge — 1 M tokens', tokens: 1000000 },
    };
    let received: QuotaAlertView | undefined;
    service.getAlert().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/usage/alert');
    expect(req.request.method).toBe('GET');
    // Le client n'envoie aucun identifiant utilisateur (isolation garantie côté backend via le JWT).
    expect(req.request.params.keys().length).toBe(0);
    req.flush(alert);
    expect(received).toEqual(alert);
  });

  it('POSTs to /api/usage/alert/dismiss without any client-supplied identifier', () => {
    let done = false;
    service.dismissAlert().subscribe(() => (done = true));

    const req = httpMock.expectOne('/api/usage/alert/dismiss');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush(null);
    expect(done).toBeTrue();
  });
});
