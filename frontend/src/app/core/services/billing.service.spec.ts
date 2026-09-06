import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { BillingService } from './billing.service';
import {
  AtelierOptionView,
  CheckoutResponse,
  PlansResponse,
  SubscriptionView,
  TopUpPacksResponse,
} from '../models/billing.models';

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
      plans: [
        { code: 'PRO', label: 'Pro', providerMode: 'HOSTED', period: 'MONTHLY', tokens: 5000000, priceEur: '99' },
      ],
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
      customerKeyBilled: false,
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

  it('GETs the top-up pack catalog from /api/billing/topups', () => {
    const packs: TopUpPacksResponse = {
      packs: [{ code: 'STANDARD', label: 'Recharge 1 M tokens', tokens: 1000000 }],
    };
    let received: TopUpPacksResponse | undefined;
    service.getTopUps().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/billing/topups');
    expect(req.request.method).toBe('GET');
    req.flush(packs);
    expect(received).toEqual(packs);
  });

  it('POSTs a top-up checkout request to /api/billing/topup/checkout', () => {
    const response: CheckoutResponse = { checkoutUrl: 'https://checkout.stripe.com/topup' };
    let received: CheckoutResponse | undefined;
    service.startTopUpCheckout('STANDARD').subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/billing/topup/checkout');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ packCode: 'STANDARD' });
    req.flush(response);
    expect(received).toEqual(response);
  });

  // ------------------------------------------------ Option Atelier (F-40 / SF-40-03)

  it('GETs the Atelier option state from /api/billing/atelier-option', () => {
    const option: AtelierOptionView = {
      priceEur: '40',
      entitled: false,
      includedInPlan: false,
      status: null,
      cancelAt: null,
      available: true,
    };
    let received: AtelierOptionView | undefined;
    service.getAtelierOption().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/billing/atelier-option');
    expect(req.request.method).toBe('GET');
    req.flush(option);
    expect(received).toEqual(option);
  });

  it('POSTs the Atelier option checkout to /api/billing/atelier-option/checkout', () => {
    const response: CheckoutResponse = { checkoutUrl: 'https://checkout.stripe.com/option' };
    let received: CheckoutResponse | undefined;
    service.startAtelierOptionCheckout().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/billing/atelier-option/checkout');
    expect(req.request.method).toBe('POST');
    req.flush(response);
    expect(received).toEqual(response);
  });

  it('POSTs the Atelier option cancellation to /api/billing/atelier-option/cancel', () => {
    const option: AtelierOptionView = {
      priceEur: '40',
      entitled: true,
      includedInPlan: false,
      status: 'ACTIVE',
      cancelAt: '2026-10-03T00:00:00Z',
      available: true,
    };
    let received: AtelierOptionView | undefined;
    service.cancelAtelierOption().subscribe((r) => (received = r));

    const req = httpMock.expectOne('/api/billing/atelier-option/cancel');
    expect(req.request.method).toBe('POST');
    req.flush(option);
    expect(received).toEqual(option);
  });
});
