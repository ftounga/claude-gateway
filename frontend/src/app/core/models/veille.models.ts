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
export function watchStateLabel(watch: Pick<JarvisWatch, 'active' | 'proposed'>): string {
  if (watch.proposed) {
    return 'Proposée — à tester puis activer';
  }
  return watch.active ? 'Active' : 'En pause';
}
