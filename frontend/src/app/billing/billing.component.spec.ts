import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { BillingComponent } from './billing.component';
import { BillingService } from '../core/services/billing.service';
import { UsageService } from '../core/services/usage.service';
import {
  PlansResponse,
  SubscriptionView,
  TopUpPacksResponse,
} from '../core/models/billing.models';
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
      { code: 'SOLO', label: 'Solo', providerMode: 'HOSTED', period: 'MONTHLY', tokens: 1000000, priceEur: '24' },
      { code: 'PRO', label: 'Pro', providerMode: 'HOSTED', period: 'MONTHLY', tokens: 5000000, priceEur: '99' },
    ],
  };
  const usage: UsageView = {
    usedTokens: 4200,
    quotaTokens: 200000,
    remainingTokens: 195800,
    periodStart: '2026-07-01',
    periodEnd: '2026-08-01',
  };
  const topUps: TopUpPacksResponse = {
    packs: [{ code: 'STANDARD', label: 'Recharge 1 M tokens', tokens: 1000000 }],
  };

  function setup(queryCheckout: string | null = null, usageFails = false): void {
    billingService = jasmine.createSpyObj<BillingService>('BillingService', [
      'getSubscription',
      'getPlans',
      'startCheckout',
      'getTopUps',
      'startTopUpCheckout',
      'changePlan',
    ]);
    billingService.getSubscription.and.returnValue(of(subscription));
    billingService.getPlans.and.returnValue(of(plans));
    billingService.getTopUps.and.returnValue(of(topUps));

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

  // --- Rachat de tokens (top-up, SF-21-03) ---

  it('loads top-up packs on init', () => {
    setup();
    expect(billingService.getTopUps).toHaveBeenCalled();
    expect(component.topUpPacks().length).toBe(1);
    expect(component.topUpPacks()[0].code).toBe('STANDARD');
  });

  it('starts a top-up checkout and redirects to the Stripe URL', () => {
    setup();
    billingService.startTopUpCheckout.and.returnValue(
      of({ checkoutUrl: 'https://checkout.stripe/topup' }),
    );

    component.buyTopUp('STANDARD');

    expect(billingService.startTopUpCheckout).toHaveBeenCalledWith('STANDARD');
    expect(
      (component as unknown as { redirect: (u: string) => void }).redirect,
    ).toHaveBeenCalledWith('https://checkout.stripe/topup');
  });

  it('does not redirect when top-up checkout fails and resets progress', () => {
    setup();
    billingService.startTopUpCheckout.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 503, error: { error: 'billing_unavailable' } })),
    );

    component.buyTopUp('STANDARD');

    expect(
      (component as unknown as { redirect: (u: string) => void }).redirect,
    ).not.toHaveBeenCalled();
    expect(component.topUpInProgress()).toBeNull();
  });

  it('keeps top-up packs empty when the catalog fails to load', () => {
    billingService = jasmine.createSpyObj<BillingService>('BillingService', [
      'getSubscription',
      'getPlans',
      'startCheckout',
      'getTopUps',
      'startTopUpCheckout',
      'changePlan',
    ]);
    billingService.getSubscription.and.returnValue(of(subscription));
    billingService.getPlans.and.returnValue(of(plans));
    billingService.getTopUps.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    usageService = jasmine.createSpyObj<UsageService>('UsageService', ['getUsage']);
    usageService.getUsage.and.returnValue(of(usage));

    TestBed.configureTestingModule({
      imports: [BillingComponent],
      providers: [
        provideNoopAnimations(),
        { provide: BillingService, useValue: billingService },
        { provide: UsageService, useValue: usageService },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    });
    fixture = TestBed.createComponent(BillingComponent);
    component = fixture.componentInstance;
    spyOn(component as unknown as { redirect: (u: string) => void }, 'redirect');
    fixture.detectChanges();

    // Section top-up simplement masquée : l'écran reste chargé.
    expect(component.topUpPacks().length).toBe(0);
    expect(component.plans().length).toBe(2);
  });

  // ---- Upgrade / downgrade (SF-21-05) ----

  it('subscribes (checkout) when the user has no active subscription', () => {
    setup(); // abonnement TRIALING (planCode null)
    billingService.startCheckout.and.returnValue(of({ checkoutUrl: 'https://checkout.stripe/x' }));

    const pro = component.plans().find((p) => p.code === 'PRO')!;
    expect(component.planActionLabel(pro)).toBe('Souscrire');
    component.onPlanAction(pro);

    expect(billingService.startCheckout).toHaveBeenCalledWith('PRO');
    expect(billingService.changePlan).not.toHaveBeenCalled();
  });

  it('changes plan (no redirect) when the user has an active subscription', () => {
    setup();
    // Abonnement actif sur SOLO : passer à PRO = upgrade via changePlan.
    component.subscription.set({
      status: 'ACTIVE',
      planCode: 'SOLO',
      trialEndsAt: null,
      currentPeriodEnd: '2026-08-01T00:00:00Z',
    });
    billingService.changePlan.and.returnValue(
      of({ status: 'ACTIVE', planCode: 'PRO', trialEndsAt: null, currentPeriodEnd: '2026-08-01T00:00:00Z' }),
    );

    const pro = component.plans().find((p) => p.code === 'PRO')!;
    const solo = component.plans().find((p) => p.code === 'SOLO')!;
    expect(component.planActionLabel(pro)).toBe('Passer à');
    expect(component.planActionLabel(solo)).toBe('Plan actuel');
    expect(component.isCurrentPlan(solo)).toBeTrue();

    component.onPlanAction(pro);

    expect(billingService.changePlan).toHaveBeenCalledWith('PRO');
    expect(billingService.startCheckout).not.toHaveBeenCalled();
    expect(component.subscription()?.planCode).toBe('PRO');
    expect(component.changeInProgress()).toBeNull();
  });

  it('surfaces an error when changing plan fails', () => {
    setup();
    component.subscription.set({
      status: 'ACTIVE',
      planCode: 'PRO',
      trialEndsAt: null,
      currentPeriodEnd: '2026-08-01T00:00:00Z',
    });
    billingService.changePlan.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { error: 'no_active_subscription' } })),
    );

    component.changePlan('SOLO');

    expect(component.changeInProgress()).toBeNull();
  });
});
