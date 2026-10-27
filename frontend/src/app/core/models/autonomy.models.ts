/**
 * **Un chantier en autonome** (F-219) — le contrat de l'API, réutilisé par F-220. Aucun identifiant de
 * compte : la gateway part du JWT.
 */

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
}

export type AutonomyRunStatus = 'RUNNING' | 'DONE' | 'BLOCKED' | 'STOPPED' | 'FAILED';

/** Une course autonome (SF-219-02). */
export interface AutonomyRun {
  id: string;
  workspaceId: string;
  chantierNumber: number;
  chantierTitle: string | null;
  status: AutonomyRunStatus | string;
  turns: number;
  stepsDone: number;
  stepsTotal: number;
  costUsd: number;
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
