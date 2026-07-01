import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { BillingComponent } from './billing.component';
import { BillingService } from '../core/services/billing.service';
import { UsageService } from '../core/services/usage.service';
import { PlansResponse, SubscriptionView } from '../core/models/billing.models';
import { UsageView } from '../core/models/usage.models';

describe('BillingComponent', () => {
  let fixture: ComponentFixture<BillingComponent>;
  let component: BillingComponent;
  let billingService: jasmine.SpyObj<BillingService>;
  let usageService: jasmine.SpyObj<UsageService>;

  const subscription: SubscriptionView = {
    status: 'TRIALING',
    planCode: null,
    trialEndsAt: '2026-07-15T00:00:00Z',
    currentPeriodEnd: null,
  };
  const plans: PlansResponse = {
    plans: [
      { code: 'SOLO', label: 'Solo', providerMode: 'HOSTED', period: 'MONTHLY' },
      { code: 'PRO', label: 'Pro', providerMode: 'HOSTED', period: 'MONTHLY' },
    ],
  };
  const usage: UsageView = {
    usedTokens: 4200,
    quotaTokens: 200000,
    remainingTokens: 195800,
    periodStart: '2026-07-01',
    periodEnd: '2026-08-01',
  };

  function setup(queryCheckout: string | null = null, usageFails = false): void {
    billingService = jasmine.createSpyObj<BillingService>('BillingService', [
      'getSubscription',
      'getPlans',
      'startCheckout',
    ]);
    billingService.getSubscription.and.returnValue(of(subscription));
    billingService.getPlans.and.returnValue(of(plans));

    usageService = jasmine.createSpyObj<UsageService>('UsageService', ['getUsage']);
    usageService.getUsage.and.returnValue(
      usageFails ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(usage),
    );

    TestBed.configureTestingModule({
      imports: [BillingComponent],
      providers: [
        provideNoopAnimations(),
        { provide: BillingService, useValue: billingService },
        { provide: UsageService, useValue: usageService },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap(
                queryCheckout ? { checkout: queryCheckout } : {},
              ),
            },
          },
        },
      ],
    });
    fixture = TestBed.createComponent(BillingComponent);
    component = fixture.componentInstance;
    // Neutralise la vraie redirection navigateur.
    spyOn(component as unknown as { redirect: (u: string) => void }, 'redirect');
    fixture.detectChanges();
  }

  it('loads subscription and plans on init', () => {
    setup();
    expect(billingService.getSubscription).toHaveBeenCalled();
    expect(billingService.getPlans).toHaveBeenCalled();
    expect(component.subscription()).toEqual(subscription);
    expect(component.plans().length).toBe(2);
    expect(component.loading()).toBeFalse();
  });

  it('starts checkout and redirects to the Stripe URL', () => {
    setup();
    billingService.startCheckout.and.returnValue(of({ checkoutUrl: 'https://checkout.stripe/x' }));

    component.subscribe('PRO');

    expect(billingService.startCheckout).toHaveBeenCalledWith('PRO');
    expect(
      (component as unknown as { redirect: (u: string) => void }).redirect,
    ).toHaveBeenCalledWith('https://checkout.stripe/x');
  });

  it('does not redirect when checkout fails and resets progress', () => {
    setup();
    billingService.startCheckout.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 503, error: { error: 'billing_unavailable' } })),
    );

    component.subscribe('PRO');

    expect(
      (component as unknown as { redirect: (u: string) => void }).redirect,
    ).not.toHaveBeenCalled();
    expect(component.checkoutInProgress()).toBeNull();
  });

  it('maps subscription statuses to design-system badges', () => {
    setup();
    expect(component.statusDisplay('ACTIVE').badgeClass).toBe('badge--success');
    expect(component.statusDisplay('TRIALING').badgeClass).toBe('badge--info');
    expect(component.statusDisplay('CANCELED').badgeClass).toBe('badge--neutral');
  });

  it('loads usage on init and exposes it', () => {
    setup();
    expect(usageService.getUsage).toHaveBeenCalled();
    expect(component.usage()).toEqual(usage);
  });

  it('computes the consumed percentage bounded to 0–100', () => {
    setup();
    expect(component.usagePercent(usage)).toBe(2); // 4200 / 200000 ≈ 2%
    expect(
      component.usagePercent({ ...usage, usedTokens: 500000, quotaTokens: 200000 }),
    ).toBe(100);
    expect(
      component.usagePercent({ ...usage, usedTokens: 0, quotaTokens: 0 }),
    ).toBe(100); // quota nul ⇒ bloqué
  });

  it('flags when the quota is reached', () => {
    setup();
    expect(component.quotaReached(usage)).toBeFalse();
    expect(
      component.quotaReached({ ...usage, usedTokens: 200000, quotaTokens: 200000 }),
    ).toBeTrue();
  });

  it('degrades gracefully when usage fails to load', () => {
    setup(null, true); // getUsage échoue dès l'init.

    // L'échec ne casse pas l'écran : abonnement et plans restent chargés, usage reste vide.
    expect(component.usage()).toBeNull();
    expect(component.subscription()).toEqual(subscription);
    expect(component.plans().length).toBe(2);
  });
});
