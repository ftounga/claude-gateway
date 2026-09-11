/** Modèle de l'API usage F-10 (contrat figé importé de SF-10-01 backend, `GET /api/usage`). */

/** Consommation de tokens de l'utilisateur courant pour la période de facturation en cours. */
export interface UsageView {
  /**
   * Tokens **facturés** sur la période — le décompte que le quota oppose (F-63). Chaque nature de
   * token y pèse son coût : une sortie davantage qu'une entrée, une lecture de cache beaucoup
   * moins. Ce n'est donc pas le volume traité, qui vit dans `processedTokens`.
   */
  usedTokens: number;
  /** Quota de tokens de la période (selon l'entitlement du plan/essai). */
  quotaTokens: number;
  /** Tokens restants (jamais négatif). */
  remainingTokens: number;
  /**
   * Volume de tokens **traités** sur la période (entrée + sortie). Informatif : c'est le chiffre
   * que montrent le rapport d'usage et la consommation par client, et il ne se compare pas au
   * quota. Absent d'un backend antérieur à F-63 : l'écran ne l'affiche alors pas.
   */
  processedTokens?: number;
  /** Premier jour de la période (mois calendaire UTC, ISO `YYYY-MM-DD`). */
  periodStart: string;
  /** Premier jour de la période suivante (borne exclusive, ISO `YYYY-MM-DD`). */
  periodEnd: string;
}
