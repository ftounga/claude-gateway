import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { UsageReportService } from './usage-report.service';
import { UsageReportView } from '../models/usage-report.models';

describe('UsageReportService', () => {
  let service: UsageReportService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [UsageReportService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UsageReportService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the usage report from /api/usage/report', () => {
    const report: UsageReportView = {
      currency: 'EUR',
      periods: [
        {
          periodStart: '2026-07-01',
          periodEnd: '2026-08-01',
          inputTokens: 12000,
          outputTokens: 8000,
          totalTokens: 20000,
          estimatedCost: 0.156,
          current: true,
        },
      ],
      totalInputTokens: 12000,
      totalOutputTokens: 8000,
      totalTokens: 20000,
      totalEstimatedCost: 0.156,
    };
    let received: UsageReportView | undefined;
    service.getReport().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/usage/report');
    expect(req.request.method).toBe('GET');
    // Le client n'envoie aucun identifiant utilisateur (isolation garantie côté backend via le JWT).
    expect(req.request.params.keys().length).toBe(0);
    req.flush(report);
    expect(received).toEqual(report);
  });
});
