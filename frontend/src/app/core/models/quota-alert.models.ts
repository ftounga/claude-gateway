/**
 * Modèle de l'API d'alerte de consommation F-42 (contrat figé importé de SF-42-01 backend,
 * `GET /api/usage/alert`).
 */

import { TopUpPack } from './billing.models';

/**
 * Alerte de consommation de l'utilisateur courant pour la période en cours.
 *
 * Les pourcentages sont **calculés côté serveur**, par la règle même qui a levé l'alerte : le
 * frontend ne les recalcule jamais, faute de quoi l'affichage pourrait dire 79 % là où la règle a
 * jugé 80 %.
 */
export interface QuotaAlertView {
  /** Vrai si l'alerte est à présenter : seuil franchi et alerte non encore écartée. */
  raised: boolean;
  /** Jetons consommés sur la période. */
  usedTokens: number;
  /** Quota effectif de la période (abonnement + jetons rachetés). */
  quotaTokens: number;
  /** Jetons restants (jamais négatif). */
  remainingTokens: number;
  /** Part du quota consommée, en pourcentage entier. */
  usedPercent: number;
  /** Seuil configuré qui déclenche l'alerte, en pourcentage entier. */
  thresholdPercent: number;
  /** Date à laquelle le quota repart (ISO `YYYY-MM-DD`). */
  periodEnd: string;
  /** Pack de recharge proposé en un clic, `null` si aucune alerte ou pack indisponible. */
  topUp: TopUpPack | null;
}
