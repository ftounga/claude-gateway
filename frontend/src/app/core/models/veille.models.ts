/**
 * **Les veilles Jarvis** (F-230) — le contrat figé dans `docs/features/F-230/SF-230-01-poser-une-veille.md`,
 * additif d'une SF à l'autre.
 */

/** Une veille, telle que la gateway la rend. */
export interface JarvisWatch {
  id: string;
  workspaceId: string;
  chantierNumber: number | null;
  name: string;
  platform: string;
  scope: string;
  command: string;
  scriptPath: string | null;
  runTimes: string[];
  timeZone: string;
  /** Imposé : `MONDAY_TO_FRIDAY`. */
  days: string;
  active: boolean;
  proposed: boolean;
  origin: 'USER' | 'AGENT';
  testedAt: string | null;
  testItemCount: number | null;
  testSummary: string | null;
  nextRunAt: string | null;
  createdAt: string;
  updatedAt: string;
  /** SF-230-02 — le dernier passage. */
  lastRunAt: string | null;
  lastRunStatus: JarvisWatchRunStatus | null;
  /** SF-230-02 — deux échecs d'affilée : la veille est en panne (D6). */
  broken: boolean;
  consecutiveFailures: number;
  /** SF-230-03 — budget de la rédaction IA et dépense du mois, en euros. */
  monthlyBudgetEur: number | null;
  monthSpentEur: number | null;
}

/** Un changement vu par une veille (SF-230-03). */
export interface JarvisWatchChange {
  kind: 'NEW' | 'GONE' | 'STATUS' | 'ASSIGNEE' | 'COMMENT' | 'UPDATED';
  id: string;
  title: string;
  before: string;
  after: string;
}

/** Une alerte de veille (SF-230-03) — le contrat que lit aussi Jarvis (F-231). */
export interface JarvisAlert {
  id: string;
  watchId: string;
  watchName: string | null;
  platform: string | null;
  workspaceId: string;
  chantierNumber: number | null;
  sentence: string;
  changes: JarvisWatchChange[];
  aiWritten: boolean;
  costEur: number | null;
  createdAt: string;
  openedAt: string | null;
  link: string;
}

/** Un changement, en mots : « AG-2 Batch : En cours → Bloqué ». */
export function changeLabel(change: JarvisWatchChange): string {
  const what = change.title ? `${change.id} ${change.title}` : change.id;
  switch (change.kind) {
    case 'NEW':
      return `${what} : nouveau${change.after ? ` (${change.after})` : ''}`;
    case 'GONE':
      return `${what} : sorti du périmètre`;
    case 'STATUS':
      return `${what} : ${change.before || '—'} → ${change.after || '—'}`;
    case 'ASSIGNEE':
      return `${what} : assigné à ${change.after || 'personne'}`;
    case 'COMMENT':
      return `${what} : nouveau commentaire « ${change.after} »`;
    case 'UPDATED':
      return `${what} : mis à jour`;
  }
}

/** L'issue d'un passage (SF-230-02). */
export type JarvisWatchRunStatus = 'BASELINE' | 'NO_CHANGE' | 'CHANGED' | 'FAILED' | 'MISSED_OFFLINE';

/** Un passage (SF-230-02), sans contenu. */
export interface JarvisWatchRun {
  id: string;
  watchId: string;
  slotAt: string | null;
  trigger: 'SCHEDULED' | 'CATCH_UP' | 'MANUAL';
  status: JarvisWatchRunStatus;
  changeCount: number;
  errorCode: string | null;
  durationMs: number | null;
  startedAt: string;
  finishedAt: string | null;
}

/** L'issue d'un passage, en mots. */
export function runStatusLabel(status: JarvisWatchRunStatus | null, changeCount = 0): string {
  switch (status) {
    case 'BASELINE':
      return 'base posée';
    case 'NO_CHANGE':
      return 'rien de neuf';
    case 'CHANGED':
      return changeCount > 1 ? `${changeCount} changements` : 'un changement';
    case 'FAILED':
      return 'sonde en échec';
    case 'MISSED_OFFLINE':
      return 'manqué — poste hors ligne';
    default:
      return 'pas encore passée';
  }
}

/** Le résultat d'un test de sonde devant le PO. */
export interface JarvisWatchTest {
  ok: boolean;
  itemCount: number | null;
  summary: string | null;
  sample: string[];
  errorMessage: string | null;
}

/** « Lundi → vendredi · 09:00, 14:00 ». */
export function watchScheduleLabel(watch: Pick<JarvisWatch, 'runTimes'>): string {
  const times = watch.runTimes.length ? watch.runTimes.join(', ') : '—';
  return `Lundi → vendredi · ${times}`;
}

/** L'état d'une veille, en mots. */
export function watchStateLabel(watch: Pick<JarvisWatch, 'active' | 'proposed' | 'broken'>): string {
  if (watch.proposed) {
    return 'Proposée — à tester puis activer';
  }
  if (watch.broken) {
    return 'En panne — à faire réparer';
  }
  return watch.active ? 'Active' : 'En pause';
}
