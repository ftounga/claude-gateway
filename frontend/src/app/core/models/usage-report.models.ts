/**
 * Modèles du rapport d'usage & coût F-16 (contrat figé importé de SF-16-01 backend,
 * `GET /api/usage/report`). Le coût est une estimation au tarif configuré côté backend.
 */

/** Consommation et coût estimé d'un mois calendaire. */
export interface UsagePeriodView {
  /** Premier jour du mois (UTC, ISO `YYYY-MM-DD`). */
  periodStart: string;
  /** Premier jour du mois suivant (borne exclusive, ISO `YYYY-MM-DD`). */
  periodEnd: string;
  /** Tokens d'entrée consommés sur la période. */
  inputTokens: number;
  /** Tokens de sortie consommés sur la période. */
  outputTokens: number;
  /** Total de tokens (entrée + sortie). */
  totalTokens: number;
  /** Coût estimé de la période (devise du rapport). */
  estimatedCost: number;
  /** `true` pour le mois calendaire UTC courant. */
  current: boolean;
}

/** Rapport d'usage & coût de l'utilisateur courant : historique mensuel + totaux sur la fenêtre. */
export interface UsageReportView {
  /** Devise du coût estimé (ex. `EUR`). */
  currency: string;
  /** Périodes triées de la plus récente à la plus ancienne. */
  periods: UsagePeriodView[];
  /** Somme des tokens d'entrée sur la fenêtre. */
  totalInputTokens: number;
  /** Somme des tokens de sortie sur la fenêtre. */
  totalOutputTokens: number;
  /** Somme des tokens sur la fenêtre. */
  totalTokens: number;
  /** Somme des coûts estimés sur la fenêtre. */
  totalEstimatedCost: number;
}
