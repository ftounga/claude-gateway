import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CheckoutRequest,
  CheckoutResponse,
  PlansResponse,
  SubscriptionView,
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

  /** Crée une session de paiement Stripe et renvoie l'URL de redirection. */
  startCheckout(planCode: string): Observable<CheckoutResponse> {
    const body: CheckoutRequest = { planCode };
    return this.http.post<CheckoutResponse>('/api/billing/checkout', body);
  }
}
