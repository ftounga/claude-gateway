/** Modèle de l'API usage F-10 (contrat figé importé de SF-10-01 backend, `GET /api/usage`). */

/** Consommation de tokens de l'utilisateur courant pour la période de facturation en cours. */
export interface UsageView {
  /** Tokens consommés sur la période. */
  usedTokens: number;
  /** Quota de tokens de la période (selon l'entitlement du plan/essai). */
  quotaTokens: number;
  /** Tokens restants (jamais négatif). */
  remainingTokens: number;
  /** Premier jour de la période (mois calendaire UTC, ISO `YYYY-MM-DD`). */
  periodStart: string;
  /** Premier jour de la période suivante (borne exclusive, ISO `YYYY-MM-DD`). */
  periodEnd: string;
}
