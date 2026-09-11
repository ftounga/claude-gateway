/** Modèles de l'API billing F-09 (contrats figés SF-09-01 / SF-09-02). */

/** Mode fournisseur d'un plan. */
export type ProviderMode = 'HOSTED' | 'BYOK';

/** Périodicité de facturation (F-09, étendue par F-43). */
export type BillingPeriod = 'MONTHLY' | 'DAILY' | 'YEARLY';

/**
 * Périodicité **achetable** (F-43). `DAILY` en est absente à dessein : c'est la nature du pass
 * journée, imposée par le catalogue, jamais un choix d'achat — le serveur la refuse en entrée.
 */
export type BillingPeriodChoice = 'MONTHLY' | 'YEARLY';

/** Statut d'un abonnement. */
export type SubscriptionStatus =
  | 'TRIALING'
  | 'ACTIVE'
  | 'PAST_DUE'
  | 'CANCELED'
  | 'INCOMPLETE';

/** Un plan du catalogue, enrichi du quota et d'un prix d'affichage (SF-21-05). */
export interface Plan {
  code: string;
  label: string;
  providerMode: ProviderMode;
  period: BillingPeriod;
  /**
   * Allocation **mensuelle** de tokens du plan — y compris pour un plan proposé à l'année (F-43) :
   * l'engagement est annuel, l'allocation reste mensuelle.
   */
  tokens: number;
  /** Montant d'affichage mensuel en EUR (ex. "24"), ou null si non configuré. */
  priceEur: string | null;
  /** Montant d'affichage **annuel** en EUR (ex. "240"), ou null si l'annuel n'est pas proposé. */
  yearlyPriceEur: string | null;
  /**
   * Vrai si ce plan est réellement souscriptible à l'année. Renvoyé par le **serveur** : l'écran ne
   * déduit jamais la disponibilité de la présence d'un prix — le serveur exige aussi un price
   * payable, que l'écran ne voit pas.
   */
  yearlyAvailable: boolean;
}

/** Requête de changement de plan et/ou de périodicité (SF-21-05, F-43). */
export interface ChangePlanRequest {
  planCode: string;
  /** Périodicité cible ; absente, le serveur retient le mensuel. */
  period?: BillingPeriodChoice;
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
  /**
   * Vrai si les appels sont servis — et facturés — par la clé Anthropic du client (offre BYOK en
   * cours, F-41). Renvoyé par le **serveur** : l'écran ne déduit jamais l'offre du code de plan,
   * il reflète la décision qui gouverne réellement le comportement (`isCustomerKeyBilled`).
   */
  customerKeyBilled: boolean;
  /**
   * Périodicité d'**engagement** de l'abonnement (F-43), ou null si aucun engagement n'est
   * enregistré (essai, ou abonnement antérieur à F-43). Ne dit **rien** du quota : l'allocation
   * reste mensuelle quelle que soit sa valeur.
   */
  billingPeriod: BillingPeriod | null;
  /**
   * Durée de l'essai gratuit, en jours, **telle que le serveur la sert** (F-66). Propriété de
   * l'**offre**, pas de l'abonnement : elle est renvoyée même à un client payant, pour que la grille
   * puisse décrire l'essai à qui n'y a plus droit. L'écran l'affiche au lieu de la réciter — c'est
   * ce qui empêche « essai 5 jours » de réapparaître à côté d'une promesse de 14.
   */
  trialDays: number;
  /** Jetons alloués par l'essai, servis par la même configuration, et affichés de la même façon. */
  trialTokens: number;
}

/** Requête de création d'une session de paiement. */
export interface CheckoutRequest {
  planCode: string;
  /** Périodicité d'engagement souhaitée ; absente, le serveur retient le mensuel (F-43). */
  period?: BillingPeriodChoice;
}

/** Réponse de création d'une session : URL de redirection Stripe. */
export interface CheckoutResponse {
  checkoutUrl: string;
}

/** Un pack de tokens rachetable ponctuellement (top-up F-21). */
export interface TopUpPack {
  code: string;
  label: string;
  tokens: number;
  /**
   * Montant d'affichage en EUR (ex. `"4,99"`), renvoyé par le **serveur** (F-67) — jamais une
   * constante d'écran. `null` quand aucun montant n'est configuré : le pack reste **vendable**, et
   * l'écran dit alors que le prix sera indiqué à l'étape de paiement. Ne **jamais** y substituer un
   * montant de repli : un chiffre inventé à côté d'un bouton d'achat est opposable par un client.
   */
  priceEur: string | null;
}

/** Réponse du catalogue de packs de tokens. */
export interface TopUpPacksResponse {
  packs: TopUpPack[];
}

/** Requête de création d'une session de rachat de tokens. */
export interface TopUpCheckoutRequest {
  packCode: string;
}

/**
 * État de l'option Atelier (F-40) : le droit d'accès à l'Atelier, découplé du plan. Souscrite en
 * supplément d'une offre Solo/Pro, elle **ne change aucun quota**.
 */
export interface AtelierOptionView {
  /** Montant d'affichage EUR (ex. "40"), renvoyé par le backend — jamais une constante d'écran. */
  priceEur: string;
  /** Droit d'accès effectif, quelle qu'en soit la source (offre Gold ou option). */
  entitled: boolean;
  /** Le droit vient de l'offre elle-même (Gold) : l'option serait sans objet. */
  includedInPlan: boolean;
  /** Statut de l'option, ou null si jamais souscrite. */
  status: SubscriptionStatus | null;
  /** Terme d'une résiliation programmée, ou null. */
  cancelAt: string | null;
  /** L'option est réellement souscriptible (paiement configuré côté serveur). */
  available: boolean;
}
