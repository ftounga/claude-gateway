/**
 * **Les bilans de session** (F-155 / SF-155-04), côté administration : ce qui a été fait, ce que ça
 * a coûté, ce qui aurait mieux valu.
 */

/** Les trois axes du PO, et aucun autre. */
export type BilanAxis = 'COUT' | 'TEMPS' | 'RAISONNEMENT';

/** Les cinq chiffres qui permettent de comparer deux semaines d'un coup d'œil. */
export interface BilanSummary {
  id: string;
  workspaceId: string;
  workspaceName: string | null;
  fromAt: string;
  toAt: string;
  /** `AUTOMATIQUE` (seuil atteint) ou `MANUEL` (demandé d'un clic). */
  origin: string;
  turns: number;
  costEur: number;
  /** La part du cache — le chiffre de F-134. */
  cacheShare: number;
  suggestionCount: number;
  /** Combien ont été écartées faute d'impact : le dire vaut mieux que les diluer. */
  discardedCount: number;
  createdAt: string;
}

/** Un tour cher, tel que le relevé le garde. */
export interface BilanCostlyTurn {
  occurredAt: string;
  model: string | null;
  costEur: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
}

/** Un outil lourd. `total` est une durée ISO-8601 (`PT4M`). */
export interface BilanHeavyTool {
  tool: string;
  calls: number;
  total: string;
  failures: number;
}

/** Le relevé : ce qui a été fait, ce que ça a coûté, où c'est parti. */
export interface BilanLedger {
  turns: number;
  elapsed: string;
  toolCalls: number;
  failedTools: number;
  filesWritten: number;
  costEur: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cacheShare: number;
  turnsWithoutCost: number;
  model: string | null;
  costliestTurns: BilanCostlyTurn[];
  heaviestTools: BilanHeavyTool[];
}

/** Une suggestion : elle cite toujours **sa mesure**, sans quoi ce serait un avis. */
export interface BilanSuggestion {
  axis: BilanAxis;
  advice: string;
  measure: string;
  gainPct: number;
  gainEur: number | null;
}

/** Un bilan ouvert. */
export interface BilanDetail {
  headline: BilanSummary;
  ledger: BilanLedger | null;
  suggestions: BilanSuggestion[];
}
