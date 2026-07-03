/** Modèles de l'API billing F-09 (contrats figés SF-09-01 / SF-09-02). */

/** Mode fournisseur d'un plan. */
export type ProviderMode = 'HOSTED' | 'BYOK';

/** Périodicité de facturation d'un plan. */
export type BillingPeriod = 'MONTHLY' | 'DAILY';

/** Statut d'un abonnement. */
export type SubscriptionStatus =
  | 'TRIALING'
  | 'ACTIVE'
  | 'PAST_DUE'
  | 'CANCELED'
  | 'INCOMPLETE';

/** Un plan du catalogue (sans prix : le prix vit côté Stripe). */
export interface Plan {
  code: string;
  label: string;
  providerMode: ProviderMode;
  period: BillingPeriod;
}

/** Réponse du catalogue de plans. */
export interface PlansResponse {
  plans: Plan[];
}

/** Abonnement de l'utilisateur courant. */
export interface SubscriptionView {
  status: SubscriptionStatus;
  planCode: string | null;
  trialEndsAt: string | null;
  currentPeriodEnd: string | null;
}

/** Requête de création d'une session de paiement. */
export interface CheckoutRequest {
  planCode: string;
}

/** Réponse de création d'une session : URL de redirection Stripe. */
export interface CheckoutResponse {
  checkoutUrl: string;
}

/** Un pack de tokens rachetable ponctuellement (top-up F-21). Le prix vit côté Stripe. */
export interface TopUpPack {
  code: string;
  label: string;
  tokens: number;
}

/** Réponse du catalogue de packs de tokens. */
export interface TopUpPacksResponse {
  packs: TopUpPack[];
}

/** Requête de création d'une session de rachat de tokens. */
export interface TopUpCheckoutRequest {
  packCode: string;
}
