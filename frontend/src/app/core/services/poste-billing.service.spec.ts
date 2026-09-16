import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { PosteBillingService } from './poste-billing.service';

describe('PosteBillingService', () => {
  let service: PosteBillingService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), PosteBillingService],
    });
    service = TestBed.inject(PosteBillingService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the settings', () => {
    let result: string | undefined;
    service.settings().subscribe((s) => (result = s.startMonth));
    const req = http.expectOne('/api/activity/settings');
    expect(req.request.method).toBe('GET');
    req.flush({ startMonth: '2025-09' });
    expect(result).toBe('2025-09');
  });

  it('sets the start month with only the field in the body (no user id)', () => {
    service.setStartMonth('2026-01').subscribe();
    const req = http.expectOne('/api/activity/settings');
    expect(req.request.method).toBe('PUT');
    expect(Object.keys(req.request.body)).toEqual(['startMonth']);
    expect(req.request.body.startMonth).toBe('2026-01');
    req.flush({ startMonth: '2026-01' });
  });

  it('reads the rates', () => {
    let result: number | undefined;
    service.rates().subscribe((r) => (result = r.length));
    const req = http.expectOne('/api/activity/rates');
    expect(req.request.method).toBe('GET');
    req.flush([{ hostId: 'h1', dailyRateCents: 55000 }]);
    expect(result).toBe(1);
  });

  it('sets a rate on the right host with only the amount in the body', () => {
    service.setRate('h1', 55000).subscribe();
    const req = http.expectOne('/api/activity/rates/h1');
    expect(req.request.method).toBe('PUT');
    expect(Object.keys(req.request.body)).toEqual(['dailyRateCents']);
    expect(req.request.body.dailyRateCents).toBe(55000);
    req.flush({ hostId: 'h1', dailyRateCents: 55000 });
  });

  it('clears a rate', () => {
    service.clearRate('h1').subscribe();
    const req = http.expectOne('/api/activity/rates/h1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
