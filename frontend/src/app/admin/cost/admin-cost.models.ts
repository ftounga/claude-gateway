/**
 * Le coût réel vu par l'administration (F-133 / SF-133-07).
 *
 * **Les montants arrivent déjà en euros**, calculés par la passerelle. L'écran n'en convertit aucun :
 * le taux de change est un réglage serveur, et le recalculer ici en ferait une seconde source de
 * vérité qui divergerait un jour.
 */

/** Les montants s'écrivent en euros (F-224 / SF-224-05) : voir `shared/euros`. */
export { formatEuros, formatRate } from '../../shared/euros';

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
  /** La dépense en dollars, la devise de la facture (F-224) — absente d'une passerelle antérieure. */
  spentUsd?: number;
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
  /** Le total en dollars (F-224 / SF-224-03). */
  spentUsd?: number;
  /** Le taux dollar → euro appliqué, écrit à côté des montants. */
  usdToEur?: number;
}

/**
 * Les périodes offertes : la semaine ISO (repart le lundi), les 7 derniers jours (F-224, comparable à une facture
 * glissante) et le mois.
 */
export type AdminCostPeriod = 'week' | '7d' | 'month';

/** Une clé API sur un jour (F-224 / SF-224-02) : sa consommation chiffrée à la grille (estimation). */
export interface ReconciliationKey {
  apiKeyId: string;
  label: string | null;
  amountUsd: number;
  /** Le même montant en euros (F-224 / SF-224-05), absent d'une passerelle antérieure. */
  amountEur?: number;
  inputTokens: number;
  outputTokens: number;
  /** La clé de la passerelle (`APP_ANTHROPIC_GATEWAY_KEY_ID`, F-224 / SF-224-06). */
  gateway?: boolean;
}

/** Un jour rapproché : facturé par le fournisseur (ou `null` s'il n'est pas encore lu), calculé, écart. */
export interface ReconciliationDay {
  day: string;
  billedUsd: number | null;
  calculatedUsd: number;
  gapUsd: number | null;
  keys: ReconciliationKey[];
  /** En euros (F-224 / SF-224-05) : facturé € (`null` non lu), calculé €, écart = facturé € − calculé €. */
  billedEur?: number | null;
  calculatedEur?: number;
  gapEur?: number | null;
  /** Aujourd'hui (UTC) : partiel, sans écart, hors des totaux (F-224 / SF-224-06). */
  partial?: boolean;
  /** La clé de la passerelle ce jour-là (`null` : non désignée ou non relue) et l'écart à périmètre égal. */
  gatewayKeyEur?: number | null;
  gatewayGapEur?: number | null;
}

/** Ce que couvre chaque montant, en clair (F-224 / SF-224-06). */
export interface ReconciliationScopes {
  billed: string;
  calculated: string;
  gatewayKey: string;
  keys: string;
  today: string;
}

/** Le rapprochement avec la facture du fournisseur (F-224 / SF-224-02). */
export interface CostReconciliation {
  configured: boolean;
  /** Comment créer la clé, tant qu'elle manque. */
  setupHint: string | null;
  lastFetchedAt: string | null;
  billedTotalUsd: number;
  calculatedTotalUsd: number;
  days: ReconciliationDay[];
  /** Le taux appliqué aux montants en euros (F-224 / SF-224-05). */
  usdToEur?: number;
  billedTotalEur?: number;
  calculatedTotalEur?: number;
  /** F-224 / SF-224-06 : le périmètre de chaque montant ; la clé de la passerelle (ou comment la désigner). */
  scopes?: ReconciliationScopes;
  gatewayApiKeyId?: string | null;
  gatewayKeyHint?: string | null;
  gatewayKeyTotalEur?: number | null;
}

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
