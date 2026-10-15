/**
 * **Les routines du sujet** (F-183) — formes échangées avec la gateway (contrat figé des mini-specs
 * SF-183-01 → 04).
 */

/** Rythme fermé (pas de cron libre). */
export type RoutineCadence = 'DAILY' | 'WEEKDAYS' | 'WEEKLY';

/** Déclarer ou modifier une routine — un champ absent garde sa valeur à la modification. */
export interface RoutineRequest {
  name?: string;
  instruction?: string;
  cadence?: RoutineCadence;
  dayOfWeek?: number | null;
  runTime?: string;
  timeZone?: string;
  maxRunUsd?: number;
  active?: boolean;
  /** Budget mensuel en dollars (SF-183-03), 1 … 2 000 $. */
  monthlyBudgetUsd?: number;
}

/** Une routine telle que l'écran la montre. */
export interface Routine {
  id: string;
  workspaceId: string;
  name: string;
  instruction: string;
  cadence: RoutineCadence;
  dayOfWeek: number | null;
  runTime: string;
  timeZone: string;
  maxRunUsd: number;
  active: boolean;
  /** Proposée par l'agent, pas encore validée. */
  proposed: boolean;
  origin: 'USER' | 'AGENT';
  /** Prochain créneau, `null` si en pause ou en proposition. */
  nextRunAt: string | null;
  createdAt: string;
  updatedAt: string;
  /** Dernière exécution (SF-183-02), absente si la routine n'a jamais tourné. */
  lastRun?: RoutineRun | null;
  /** Budget mensuel en dollars (SF-183-03). */
  monthlyBudgetUsd?: number;
  /** Dépensé depuis le 1er du mois, dans le fuseau de la routine (SF-183-03). */
  monthSpentUsd?: number;
}

/** Part du budget du mois consommée, bornée à 100 % (barre). */
export function monthPercent(routine: Pick<Routine, 'monthlyBudgetUsd' | 'monthSpentUsd'>): number {
  const budget = routine.monthlyBudgetUsd ?? 0;
  if (budget <= 0) {
    return 0;
  }
  return Math.min(100, Math.round(((routine.monthSpentUsd ?? 0) / budget) * 100));
}

/** L'état d'une exécution (SF-183-02). */
export type RoutineRunStatus =
  | 'RUNNING' | 'DONE' | 'INCOMPLETE' | 'FAILED' | 'INTERRUPTED' | 'MISSED_OFFLINE' | 'SKIPPED_BUDGET';

/** Une exécution de routine : des instants, un statut, un coût — aucun contenu. */
export interface RoutineRun {
  id: string;
  routineId: string;
  slotAt: string | null;
  trigger: 'SCHEDULED' | 'CATCH_UP' | 'MANUAL';
  status: RoutineRunStatus;
  startedAt: string;
  finishedAt: string | null;
  costUsd: number | null;
  capUsd: number | null;
  errorCode: string | null;
  summary: string | null;
}

/** Le statut d'une exécution en clair. */
export function runStatusLabel(run: Pick<RoutineRun, 'status' | 'trigger' | 'errorCode'>): string {
  switch (run.status) {
    case 'RUNNING':
      return 'En cours';
    case 'DONE':
      return run.trigger === 'CATCH_UP' ? 'Rattrapée' : 'Terminée';
    case 'INCOMPLETE':
      return 'Incomplète — budget atteint';
    case 'INTERRUPTED':
      return 'Interrompue';
    case 'MISSED_OFFLINE':
      return 'Manquée — poste hors ligne';
    case 'SKIPPED_BUDGET':
      return 'Non lancée — budget du mois atteint';
    case 'FAILED':
      return run.errorCode ? `Échec (${run.errorCode})` : 'Échec';
  }
}

/** Les jours, 1 = lundi … 7 = dimanche. */
export const ROUTINE_DAYS: readonly { value: number; label: string }[] = [
  { value: 1, label: 'Lundi' },
  { value: 2, label: 'Mardi' },
  { value: 3, label: 'Mercredi' },
  { value: 4, label: 'Jeudi' },
  { value: 5, label: 'Vendredi' },
  { value: 6, label: 'Samedi' },
  { value: 7, label: 'Dimanche' },
];

/** « Chaque jour ouvré à 08:00 », « Chaque vendredi à 17:00 ». */
export function cadenceLabel(routine: Pick<Routine, 'cadence' | 'dayOfWeek' | 'runTime'>): string {
  switch (routine.cadence) {
    case 'DAILY':
      return `Chaque jour à ${routine.runTime}`;
    case 'WEEKDAYS':
      return `Chaque jour ouvré à ${routine.runTime}`;
    case 'WEEKLY': {
      const day = ROUTINE_DAYS.find((d) => d.value === routine.dayOfWeek)?.label.toLowerCase() ?? '…';
      return `Chaque ${day} à ${routine.runTime}`;
    }
  }
}

/** Un montant en dollars, en français : « 5,00 $ ». */
export function usd(value: number | null | undefined): string {
  return `${(value ?? 0).toFixed(2).replace('.', ',')} $`;
}

/** Une date courte en français : « lun. 19 oct., 08:00 ». */
export function shortDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return new Intl.DateTimeFormat('fr-FR', {
    weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
  }).format(date);
}

/** Le fuseau du navigateur — celui du poste où l'on déclare la routine. */
export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Europe/Paris';
  } catch {
    return 'Europe/Paris';
  }
}
