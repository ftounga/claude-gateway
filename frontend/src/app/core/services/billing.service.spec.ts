import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { BillingService } from './billing.service';
import { CheckoutResponse, PlansResponse, SubscriptionView } from '../models/billing.models';

describe('BillingService', () => {
  let service: BillingService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [BillingService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BillingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the plan catalog from /api/billing/plans', () => {
    const plans: PlansResponse = {
      plans: [{ code: 'PRO', label: 'Pro', providerMode: 'HOSTED', period: 'MONTHLY' }],
    };
    let received: PlansResponse | undefined;
    service.getPlans().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/billing/plans');
    expect(req.request.method).toBe('GET');
    req.flush(plans);
    expect(received).toEqual(plans);
  });

  it('GETs the current subscription from /api/billing/subscription', () => {
    const sub: SubscriptionView = {
      status: 'TRIALING',
      planCode: null,
      trialEndsAt: '2026-07-15T00:00:00Z',
      currentPeriodEnd: null,
    };
    let received: SubscriptionView | undefined;
    service.getSubscription().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/billing/subscription');
    expect(req.request.method).toBe('GET');
    req.flush(sub);
    expect(received).toEqual(sub);
  });

  it('POSTs a checkout request to /api/billing/checkout', () => {
    const response: CheckoutResponse = { checkoutUrl: 'https://checkout.stripe.com/x' };
    let received: CheckoutResponse | undefined;
    service.startCheckout('PRO').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/billing/checkout');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ planCode: 'PRO' });
    req.flush(response);
    expect(received).toEqual(response);
  });
});
