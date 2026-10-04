import { MapDeadline, MapGraph } from '../../core/models/governance.models';

/**
 * **Les signaux de la carte** (F-173 / SF-173-06), en fonctions pures : l'échéancier et ce qui
 * demande attention, calculés sur le plan déjà lu.
 */

/** Une échéance « bientôt » : dans ce nombre de jours (aligné sur F-174 SF-04, ±14 j). */
export const SOON_DAYS = 14;

const DAY_MS = 24 * 60 * 60 * 1000;

/** Le jour d'une date ISO (AAAA-MM-JJ…), à minuit UTC — les échéances sont des dates sans heure. */
function dayOf(iso: string): number {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number);
  return Date.UTC(y, (m || 1) - 1, d || 1);
}

/** Le jour local d'aujourd'hui, ramené à minuit UTC pour se comparer aux échéances. */
export function todayKey(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** Jours de `from` à `to` (négatif si `to` est passé). */
export function daysBetween(from: string, to: string): number {
  return Math.round((dayOf(to) - dayOf(from)) / DAY_MS);
}

export interface DatedDeadline extends MapDeadline {
  days: number;
}

export interface DeadlineGroups {
  overdue: DatedDeadline[];
  soon: DatedDeadline[];
  later: DatedDeadline[];
}

/** Les échéances, groupées : dépassées, dans les {@link SOON_DAYS} jours, plus tard. Triées par date. */
export function deadlineGroups(deadlines: MapDeadline[], today: string): DeadlineGroups {
  const sorted = [...deadlines].sort((a, b) => a.dueOn.localeCompare(b.dueOn));
  const groups: DeadlineGroups = { overdue: [], soon: [], later: [] };
  for (const deadline of sorted) {
    const days = daysBetween(today, deadline.dueOn);
    const dated = { ...deadline, days };
    if (days < 0) {
      groups.overdue.push(dated);
    } else if (days <= SOON_DAYS) {
      groups.soon.push(dated);
    } else {
      groups.later.push(dated);
    }
  }
  return groups;
}

/** Le compteur du bouton « Signaux » : échéances dépassées ou dans les 14 jours. */
export function signalCount(graph: MapGraph | null, today: string): number {
  if (!graph || !graph.indexed) {
    return 0;
  }
  return graph.deadlines.filter((d) => daysBetween(today, d.dueOn) <= SOON_DAYS).length;
}

export interface SignalSummary {
  traps: number;
  stale: number;
  overdue: number;
  toMap: number;
}

/** Le résumé : pièges, ressources périmées, échéances dépassées, « à cartographier ». */
export function signalSummary(graph: MapGraph, today: string): SignalSummary {
  return {
    traps: graph.nodes.reduce((sum, n) => sum + n.traps, 0),
    stale: graph.nodes.filter((n) => n.stale).length,
    overdue: graph.deadlines.filter((d) => daysBetween(today, d.dueOn) < 0).length,
    toMap: graph.toMap.length,
  };
}

/** « dépassée depuis 3 j », « aujourd'hui », « dans 5 j ». */
export function dueLabel(days: number): string {
  if (days < 0) {
    return `dépassée depuis ${-days} j`;
  }
  return days === 0 ? "aujourd'hui" : `dans ${days} j`;
}
