import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AtelierOptionView,
  BillingPeriodChoice,
  ChangePlanRequest,
  CheckoutRequest,
  CheckoutResponse,
  PlansResponse,
  SubscriptionView,
  TopUpCheckoutRequest,
  TopUpPacksResponse,
} from '../models/billing.models';

/**
 * Accès à l'API de facturation F-09. Le frontend ne communique qu'avec Claude Gateway (`/api/...`),
 * jamais directement avec Stripe. L'isolation des données est garantie côté backend via le `user_id`
 * porté par le JWT (ajouté par l'`authInterceptor`).
 */
@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);

  /** Catalogue des plans proposés. */
  getPlans(): Observable<PlansResponse> {
    return this.http.get<PlansResponse>('/api/billing/plans');
  }

  /** Abonnement de l'utilisateur courant (essai provisionné à la volée côté backend). */
  getSubscription(): Observable<SubscriptionView> {
    return this.http.get<SubscriptionView>('/api/billing/subscription');
  }

  /**
   * Crée une session de paiement Stripe et renvoie l'URL de redirection.
   *
   * La périodicité est **omise** du corps quand elle n'est pas fournie : le serveur retient alors le
   * mensuel, et le contrat d'origine reste envoyé à l'octet près.
   */
  startCheckout(planCode: string, period?: BillingPeriodChoice): Observable<CheckoutResponse> {
    const body: CheckoutRequest = period ? { planCode, period } : { planCode };
    return this.http.post<CheckoutResponse>('/api/billing/checkout', body);
  }

  /** Change le plan et/ou la périodicité de l'abonnement existant (SF-21-05, F-43). */
  changePlan(planCode: string, period?: BillingPeriodChoice): Observable<SubscriptionView> {
    const body: ChangePlanRequest = period ? { planCode, period } : { planCode };
    return this.http.post<SubscriptionView>('/api/billing/subscription/change', body);
  }

  /** Catalogue des packs de tokens rachetables (top-up F-21). */
  getTopUps(): Observable<TopUpPacksResponse> {
    return this.http.get<TopUpPacksResponse>('/api/billing/topups');
  }

  /** Crée une session de paiement one-shot pour le rachat d'un pack de tokens. */
  startTopUpCheckout(packCode: string): Observable<CheckoutResponse> {
    const body: TopUpCheckoutRequest = { packCode };
    return this.http.post<CheckoutResponse>('/api/billing/topup/checkout', body);
  }

  /** État de l'option Atelier (F-40) : prix, droit effectif, statut, résiliation programmée. */
  getAtelierOption(): Observable<AtelierOptionView> {
    return this.http.get<AtelierOptionView>('/api/billing/atelier-option');
  }

  /** Souscrit l'option Atelier : renvoie l'URL de paiement de l'abonnement supplémentaire. */
  startAtelierOptionCheckout(): Observable<CheckoutResponse> {
    return this.http.post<CheckoutResponse>('/api/billing/atelier-option/checkout', {});
  }

  /** Résilie l'option Atelier en fin de période : l'accès reste ouvert jusqu'au terme payé. */
  cancelAtelierOption(): Observable<AtelierOptionView> {
    return this.http.post<AtelierOptionView>('/api/billing/atelier-option/cancel', {});
  }
}
