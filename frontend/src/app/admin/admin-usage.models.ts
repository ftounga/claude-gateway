/**
 * Modèles de la consommation par utilisateur, côté console d'administration
 * (F-61 / SF-61-03, `GET /api/admin/usage`).
 *
 * <p>Des **volumes** et des **coûts**, jamais des contenus — et **aucun nom de projet ni de
 * poste** : le nom d'une mission appartient au client de l'utilisateur, pas à la plateforme.</p>
 */

/** Un mois de consommation d'un compte. */
export interface AdminMonthUsage {
  /** Premier jour du mois (ISO `YYYY-MM-DD`). */
  periodStart: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  estimatedCost: number;
}

/** Consommation d'un compte sur la fenêtre. */
export interface AdminUserUsage {
  userId: string;
  email: string | null;
  role: string | null;
  planCode: string | null;
  subscriptionStatus: string | null;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  estimatedCost: number;
  /** Part du total de la plateforme, entre 0 et 1. */
  share: number;
  /** Évolution mensuelle, du plus ancien au plus récent. */
  periods: AdminMonthUsage[];
}

/** Réponse complète : totaux de la plateforme et comptes, du plus consommateur au moins. */
export interface AdminUsageView {
  currency: string;
  from: string;
  to: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  estimatedCost: number;
  users: AdminUserUsage[];
}
