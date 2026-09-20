/**
 * Le coût réel vu par l'administration (F-133 / SF-133-07).
 *
 * **Les montants arrivent déjà en euros**, calculés par la passerelle. L'écran n'en convertit aucun :
 * le taux de change est un réglage serveur, et le recalculer ici en ferait une seconde source de
 * vérité qui divergerait un jour.
 */

/** Une ligne de l'écran : un client, sa dépense, son budget. */
export interface AdminCostClient {
  hostId: string | null;
  hostName: string | null;
  spentEur: number;
  /** `null` quand le client n'a pas de budget : l'écran n'affiche alors **aucune** part. */
  budgetEur: number | null;
  /** `null` sans budget — jamais 0, qui se lirait « rien consommé ». */
  percent: number | null;
  /** Vrai si le client a son **propre** budget ; faux s'il hérite du défaut (rien à retirer). */
  ownBudget: boolean;
  totalTokens: number;
}

/** La synthèse d'une période. */
export interface AdminCostSummary {
  period: AdminCostPeriod;
  from: string;
  to: string;
  spentEur: number;
  budgetEur: number | null;
  percent: number | null;
  clients: AdminCostClient[];
}

/** Les deux périodes offertes. Le mois se lit par cumul ; rien de plus fin que la semaine. */
export type AdminCostPeriod = 'week' | 'month';

/** Une alerte en cours (F-133 / SF-133-06), calculée à la lecture par la passerelle. */
export interface AdminCostAlert {
  scope: 'HOST' | 'TOTAL';
  hostId: string | null;
  hostName: string | null;
  spentEur: number;
  budgetEur: number;
  percent: number;
  level: 'NEAR' | 'EXCEEDED';
  weekStart: string;
}

/** Les budgets tels que l'API les rend. */
export interface AdminCostBudgets {
  defaultAmountEur: number | null;
  hosts: { hostId: string; amountEur: number; updatedAt: string }[];
}
