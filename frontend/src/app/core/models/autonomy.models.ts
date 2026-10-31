import { UnblockerMark } from './client-board.models';

/**
 * **Un chantier en autonome** (F-219) — le contrat de l'API, réutilisé par F-220. Aucun identifiant de
 * compte : la gateway part du JWT.
 */

/** Le mode d'une course : tout le chantier (préflight vert), ou avancer jusqu'au blocage (SF-219-05). */
export type AutonomyMode = 'STRICT' | 'UNTIL_BLOCKED';

/** « Avancer jusqu'au blocage » (SF-219-05) : possible, les étapes à portée, ce qui restera. */
export interface ChantierAdvance {
  possible: boolean;
  /** Rangs (à partir de 1) des étapes à la portée de l'outil. */
  toolSteps: number[];
  /** Ce qui restera : causes premières `MOI` et `AUTRUI`, dans l'ordre du plan. */
  remaining: UnblockerMark[];
  /** La phrase lisible telle quelle. */
  label: string | null;
}

/** Une raison qui empêche le départ (SF-219-01). */
export interface AutonomyReason {
  code: string;
  /** La phrase lisible telle quelle. */
  label: string;
  /** Le rang de l'étape concernée, ou `null`. */
  step: number | null;
}

/** Le contrôle avant départ d'un chantier (SF-219-01). */
export interface ChantierPreflight {
  number: number;
  ready: boolean;
  reasons: AutonomyReason[];
  /** SF-219-05 ; absent d'une réponse ancienne. */
  advance?: ChantierAdvance | null;
}

export type AutonomyRunStatus = 'RUNNING' | 'DONE' | 'PARTIAL' | 'BLOCKED' | 'STOPPED' | 'FAILED';

/** Une course autonome (SF-219-02). */
export interface AutonomyRun {
  id: string;
  workspaceId: string;
  chantierNumber: number;
  chantierTitle: string | null;
  status: AutonomyRunStatus | string;
  /** SF-219-05 ; absent d'une réponse ancienne (= STRICT). */
  mode?: AutonomyMode | string;
  turns: number;
  stepsDone: number;
  stepsTotal: number;
  costUsd: number;
  /** Le même coût en euros, converti par la passerelle (F-224 / SF-224-05) ; absent d'une réponse ancienne. */
  costEur?: number;
  stopReason: string | null;
  errorCode: string | null;
  startedAt: string;
  finishedAt: string | null;
}

/** L'estimation affichée au lancement (pas de confirmation). */
export interface AutonomyEstimate {
  usd: number;
  turns: number;
  basis: string;
  /** Le même coût en euros (F-224 / SF-224-05) ; absent d'une réponse ancienne. */
  eur?: number;
}

/** Un chantier ouvert du sujet, vu par l'autonomie. */
export interface AutonomyBoardEntry {
  number: number;
  title: string | null;
  preflight: ChantierPreflight;
  /** La course en cours, ou la dernière ; `null` si le chantier n'est jamais parti. */
  run: AutonomyRun | null;
  estimate: AutonomyEstimate | null;
}

/** L'autonomie des chantiers d'un sujet. */
export interface AutonomyBoard {
  chantiers: AutonomyBoardEntry[];
  /** Courses en cours sur le compte. */
  running: number;
  /** Courses en cours au plus sur le compte. */
  maxParallel: number;
}

/** Le refus d'un départ (409). */
export interface AutonomyConflict {
  error: string;
  message: string;
  reasons?: AutonomyReason[];
}

/** « terminée », « bloquée »… — l'issue d'une course close. */
export function autonomyOutcomeLabel(status: string): string {
  switch (status) {
    case 'DONE': return 'terminée';
    case 'PARTIAL': return 'avancée jusqu’au blocage';
    case 'BLOCKED': return 'bloquée';
    case 'STOPPED': return 'arrêtée';
    case 'FAILED': return 'en échec';
    default: return 'en cours';
  }
}

/** L'étape en cours d'une course : faites + 1, bornée au total. */
export function autonomyCurrentStep(done: number, total: number): number {
  return total <= 0 ? 0 : Math.min(total, done + 1);
}

/** « En autonome — étape 3/7 » ou « Jusqu'au blocage — étape 3/7 » selon le mode de la course. */
export function autonomyRunningLabel(run: Pick<AutonomyRun, 'mode'>, step: number, total: number): string {
  const prefix = run.mode === 'UNTIL_BLOCKED' ? 'Jusqu’au blocage' : 'En autonome';
  return `${prefix} — étape ${step}/${total}`;
}

/** Ce qui restera, groupé : « Ça attend votre décision » et « On attend quelqu'un » (SF-219-05). */
export function groupRemaining(advance: ChantierAdvance | null | undefined): {
  decisions: UnblockerMark[];
  waits: UnblockerMark[];
} {
  const remaining = advance?.remaining ?? [];
  return {
    decisions: remaining.filter((m) => m.who === 'MOI'),
    waits: remaining.filter((m) => m.who === 'AUTRUI'),
  };
}
