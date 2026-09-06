import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { BillingComponent } from './billing.component';
import { ApiKeyService } from '../core/services/api-key.service';
import { BillingService } from '../core/services/billing.service';
import { UsageService } from '../core/services/usage.service';
import {
  AtelierOptionView,
  PlansResponse,
  SubscriptionView,
  TopUpPacksResponse,
} from '../core/models/billing.models';
import { ApiKeyStatus } from '../core/models/api-key.models';
import { UsageView } from '../core/models/usage.models';

describe('BillingComponent', () => {
  let fixture: ComponentFixture<BillingComponent>;
  let component: BillingComponent;
  let billingService: jasmine.SpyObj<BillingService>;
  let usageService: jasmine.SpyObj<UsageService>;
  let apiKeyService: jasmine.SpyObj<ApiKeyService>;

  const subscription: SubscriptionView = {
    status: 'TRIALING',
    planCode: null,
    trialEndsAt: '2026-07-15T00:00:00Z',
    currentPeriodEnd: null,
    customerKeyBilled: false,
  };
  /** Abonnement BYOK en cours (F-41) : le serveur dit que les jetons sont sur la clé du client. */
  const byokSubscription: SubscriptionView = {
    status: 'ACTIVE',
    planCode: 'BYOK',
    trialEndsAt: null,
    currentPeriodEnd: '2026-08-01T00:00:00Z',
    customerKeyBilled: true,
  };
  /** Clé BYOK enregistrée ET active : le mode vaut BYOK. */
  const activeKey: ApiKeyStatus = {
    present: true,
    maskedKey: 'sk-…AB12',
    last4: 'AB12',
    provider: 'ANTHROPIC',
    mode: 'BYOK',
    validatedAt: '2026-07-01T00:00:00Z',
    createdAt: '2026-07-01T00:00:00Z',
  };
  /** Clé enregistrée mais DÉSACTIVÉE (retour en mode Hosted) : elle ne sert aucun appel. */
  const inactiveKey: ApiKeyStatus = { ...activeKey, mode: 'HOSTED' };
  /** Aucune clé enregistrée. */
  const absentKey: ApiKeyStatus = {
    present: false,
    maskedKey: null,
    last4: null,
    provider: null,
    mode: 'HOSTED',
    validatedAt: null,
    createdAt: null,
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
  /** Option Atelier (F-40) : Solo sans option, paiement configuré. */
  const optionAvailable: AtelierOptionView = {
    priceEur: '40',
    entitled: false,
    includedInPlan: false,
    status: null,
    cancelAt: null,
    available: true,
  };

  /** Réglages F-41 : abonnement à servir, statut de clé (ou échec de l'appel). */
  interface ByokSetup {
    subscription?: SubscriptionView;
    apiKey?: ApiKeyStatus | 'fails';
  }

  function setup(
    queryCheckout: string | null = null,
    usageFails = false,
    option: AtelierOptionView | null = optionAvailable,
    byok: ByokSetup = {},
  ): void {
    billingService = jasmine.createSpyObj<BillingService>('BillingService', [
      'getSubscription',
      'getPlans',
      'startCheckout',
      'getTopUps',
      'startTopUpCheckout',
      'changePlan',
      'getAtelierOption',
      'startAtelierOptionCheckout',
      'cancelAtelierOption',
    ]);
    billingService.getSubscription.and.returnValue(of(byok.subscription ?? subscription));
    billingService.getPlans.and.returnValue(of(plans));
    billingService.getTopUps.and.returnValue(of(topUps));
    billingService.getAtelierOption.and.returnValue(
      option
        ? of(option)
        : throwError(() => new HttpErrorResponse({ status: 500 })),
    );

    apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    const keyStatus = byok.apiKey ?? absentKey;
    apiKeyService.getStatus.and.returnValue(
      keyStatus === 'fails'
        ? throwError(() => new HttpErrorResponse({ status: 500 }))
        : of(keyStatus),
    );

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
        { provide: ApiKeyService, useValue: apiKeyService },
        provideRouter([]),
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
      'getAtelierOption',
      'startAtelierOptionCheckout',
      'cancelAtelierOption',
    ]);
    billingService.getSubscription.and.returnValue(of(subscription));
    billingService.getPlans.and.returnValue(of(plans));
    billingService.getTopUps.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    billingService.getAtelierOption.and.returnValue(of(optionAvailable));

    apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    apiKeyService.getStatus.and.returnValue(of(absentKey));

    usageService = jasmine.createSpyObj<UsageService>('UsageService', ['getUsage']);
    usageService.getUsage.and.returnValue(of(usage));

    TestBed.configureTestingModule({
      imports: [BillingComponent],
      providers: [
        provideNoopAnimations(),
        { provide: BillingService, useValue: billingService },
        { provide: UsageService, useValue: usageService },
        { provide: ApiKeyService, useValue: apiKeyService },
        provideRouter([]),
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
      customerKeyBilled: false,
    });
    billingService.changePlan.and.returnValue(
      of({
        status: 'ACTIVE' as const,
        planCode: 'PRO',
        trialEndsAt: null,
        currentPeriodEnd: '2026-08-01T00:00:00Z',
        customerKeyBilled: false,
      }),
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

  it('affiche la mention Atelier sur la carte GOLD uniquement', () => {
    setup();
    // Par défaut (SOLO/PRO), aucune carte ne porte la mention Atelier.
    expect(fixture.nativeElement.textContent).not.toContain('Atelier (Claude Code Lite) inclus');

    // Ajoute une offre GOLD : sa carte doit porter la mention.
    component.plans.set([
      ...plans.plans,
      { code: 'GOLD', label: 'Gold', providerMode: 'HOSTED', period: 'MONTHLY', tokens: 12000000, priceEur: '199' },
    ]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Atelier (Claude Code Lite) inclus');
  });

  it('surfaces an error when changing plan fails', () => {
    setup();
    component.subscription.set({
      status: 'ACTIVE',
      planCode: 'PRO',
      trialEndsAt: null,
      currentPeriodEnd: '2026-08-01T00:00:00Z',
      customerKeyBilled: false,
    });
    billingService.changePlan.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { error: 'no_active_subscription' } })),
    );

    component.changePlan('SOLO');

    expect(component.changeInProgress()).toBeNull();
  });

  // ------------------------------------------------ Option Atelier (F-40 / SF-40-03)

  /** Rejoue le chargement de l'option avec un état donné, puis rend. */
  function withOption(option: AtelierOptionView): void {
    component.atelierOption.set(option);
    fixture.detectChanges();
  }

  it('shows the option price returned by the API, never a hard-coded one', () => {
    setup();
    expect(fixture.nativeElement.textContent).toContain('40 €');

    withOption({ ...optionAvailable, priceEur: '59' });

    expect(fixture.nativeElement.textContent).toContain('59 €');
    expect(fixture.nativeElement.textContent).not.toContain('40 €');
  });

  it('says the option does not change the token quota', () => {
    setup();
    expect(fixture.nativeElement.textContent).toContain('quota de tokens ne change pas');
  });

  it('offers no purchase to a plan that already includes the Atelier', () => {
    setup();
    withOption({ ...optionAvailable, entitled: true, includedInPlan: true });

    expect(fixture.nativeElement.textContent).toContain('Incluse dans votre offre');
    expect(fixture.nativeElement.textContent).not.toContain("Ajouter l'option");
    expect(component.canSubscribeAtelierOption()).toBeFalse();
  });

  it('starts the option checkout and redirects to the payment URL', () => {
    setup();
    billingService.startAtelierOptionCheckout.and.returnValue(
      of({ checkoutUrl: 'https://checkout.stripe/option' }),
    );

    component.subscribeAtelierOption();

    expect(billingService.startAtelierOptionCheckout).toHaveBeenCalled();
    expect(
      (component as unknown as { redirect: (u: string) => void }).redirect,
    ).toHaveBeenCalledWith('https://checkout.stripe/option');
  });

  it('tells a trial user to subscribe a plan first and refreshes the state', () => {
    setup();
    billingService.startAtelierOptionCheckout.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 409, error: { error: 'no_active_subscription' } }),
      ),
    );

    component.subscribeAtelierOption();

    expect(component.atelierOptionInProgress()).toBeFalse();
    // Deux appels : celui de l'init, puis la relecture après refus.
    expect(billingService.getAtelierOption).toHaveBeenCalledTimes(2);
    expect(
      (component as unknown as { redirect: (u: string) => void }).redirect,
    ).not.toHaveBeenCalled();
  });

  it('disables the button when payment is not configured', () => {
    setup();
    withOption({ ...optionAvailable, available: false });

    expect(component.canSubscribeAtelierOption()).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('Bientôt disponible');
  });

  it('hides the section when the option state cannot be loaded', () => {
    setup(null, false, null);

    expect(component.atelierOption()).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Option Atelier');
    // L'écran reste utilisable : les offres sont bien là.
    expect(component.plans().length).toBe(2);
  });

  it('asks for confirmation before cancelling, and calls nothing when refused', () => {
    setup();
    withOption({ ...optionAvailable, entitled: true, status: 'ACTIVE' });
    const dialog = TestBed.inject(MatDialog);
    spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(false) } as MatDialogRef<unknown>);

    component.cancelAtelierOption();

    expect(dialog.open).toHaveBeenCalled();
    expect(billingService.cancelAtelierOption).not.toHaveBeenCalled();
  });

  it('cancels the option once confirmed and shows the scheduled end', () => {
    setup();
    withOption({ ...optionAvailable, entitled: true, status: 'ACTIVE' });
    const dialog = TestBed.inject(MatDialog);
    spyOn(dialog, 'open').and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown>);
    billingService.cancelAtelierOption.and.returnValue(
      of({
        ...optionAvailable,
        entitled: true,
        status: 'ACTIVE',
        cancelAt: '2026-10-03T00:00:00Z',
      }),
    );

    component.cancelAtelierOption();
    fixture.detectChanges();

    expect(billingService.cancelAtelierOption).toHaveBeenCalled();
    expect(component.atelierOptionEnding()).toBeTrue();
    expect(component.atelierOptionInProgress()).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('Résiliation programmée');
    expect(fixture.nativeElement.textContent).not.toContain("Résilier l'option");
  });

  it('surfaces a readable message for each option refusal', () => {
    setup();
    const message = (code: string) =>
      (
        component as unknown as {
          atelierOptionErrorMessage: (e: HttpErrorResponse) => string;
        }
      ).atelierOptionErrorMessage(new HttpErrorResponse({ status: 409, error: { error: code } }));

    expect(message('no_active_subscription')).toContain('Solo ou Pro');
    expect(message('atelier_option_included')).toContain('déjà inclus');
    expect(message('atelier_option_already_active')).toContain('déjà active');
    expect(message('atelier_option_not_active')).toContain('à résilier');
    expect(message('billing_unavailable')).toContain('indisponible');
    expect(message('unexpected_code')).toContain("l'option Atelier");
  });

  // ------------------------------------------------ Offre BYOK (F-41 / SF-41-03)

  it('reminds a BYOK subscriber with no key where to add one', () => {
    setup(null, false, optionAvailable, { subscription: byokSubscription, apiKey: absentKey });

    expect(component.isCustomerKeyBilled()).toBeTrue();
    expect(component.showByokKeyReminder()).toBeTrue();

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain("Votre clé Anthropic n'est pas enregistrée");
    expect(html).toContain('/settings');
  });

  it('drops the reminder once an active key is registered', () => {
    setup(null, false, optionAvailable, { subscription: byokSubscription, apiKey: activeKey });

    expect(component.hasActiveApiKey()).toBeTrue();
    expect(component.showByokKeyReminder()).toBeFalse();
  });

  it('still reminds when the key exists but is deactivated', () => {
    // Une clé désactivée (retour en mode Hosted) ne sert aucun appel : le serveur la traite comme
    // absente, l'écran doit dire la même chose — sinon il rassurerait à tort.
    setup(null, false, optionAvailable, { subscription: byokSubscription, apiKey: inactiveKey });

    expect(component.hasActiveApiKey()).toBeFalse();
    expect(component.showByokKeyReminder()).toBeTrue();
  });

  it('never reminds a hosted subscriber, key or not', () => {
    setup(null, false, optionAvailable, { apiKey: absentKey });

    expect(component.isCustomerKeyBilled()).toBeFalse();
    expect(component.showByokKeyReminder()).toBeFalse();
  });

  it('keeps the screen usable when the key status cannot be read', () => {
    // Échec non bloquant : mieux vaut ne rien dire qu'alarmer à tort. Le refus serveur reste, lui.
    setup(null, false, optionAvailable, { subscription: byokSubscription, apiKey: 'fails' });

    expect(component.apiKeyStatus()).toBeNull();
    expect(component.showByokKeyReminder()).toBeFalse();
    expect(component.plans().length).toBe(2);
  });

  it('never shows a zero quota as a blocked account for a BYOK subscriber', () => {
    // Le cœur de la subfeature : quota 0 par contrat. `usagePercent` rend 100 et `quotaReached`
    // est vrai — afficher la jauge servirait « Quota atteint » à un client à jour.
    setup(null, false, optionAvailable, {
      subscription: byokSubscription,
      apiKey: activeKey,
    });

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).not.toContain('Quota atteint');
    expect(html).toContain('aucun quota plateforme');
  });

  it('says « aucun jeton inclus » on the BYOK offer card, never « 0 tokens inclus »', () => {
    setup();
    billingService.getPlans.and.returnValue(
      of({
        plans: [
          ...plans.plans,
          {
            code: 'BYOK',
            label: 'BYOK',
            providerMode: 'BYOK' as const,
            period: 'MONTHLY' as const,
            tokens: 0,
            priceEur: '29',
          },
        ],
      }),
    );
    component.loadPlans();
    fixture.detectChanges();

    // La carte est cherchée par son nom : un `innerHTML.not.toContain('0 tokens inclus')` passerait
    // pour un faux ami — « 5 000 000 tokens inclus » contient cette chaîne.
    const cards = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('article.billing__card'),
    );
    const byokCard = cards.find(
      (c) => c.querySelector('.billing__card-name')?.textContent?.trim() === 'BYOK',
    )!;
    const soloCard = cards.find(
      (c) => c.querySelector('.billing__card-name')?.textContent?.trim() === 'Solo',
    )!;

    expect(byokCard.textContent).toContain('Aucun jeton inclus');
    expect(byokCard.textContent).not.toContain('tokens inclus / mois');
    expect(byokCard.textContent).toContain('Atelier (Claude Code Lite) inclus');
    // Non-régression : l'offre Hosted voisine garde exactement son libellé.
    expect(soloCard.textContent).toContain('tokens inclus / mois');
    expect(soloCard.textContent).not.toContain('Aucun jeton inclus');
  });

  it('keeps the quota gauge for a hosted subscriber', () => {
    setup();

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('mat-progress-bar');
    expect(html).not.toContain('aucun quota plateforme');
  });
});
