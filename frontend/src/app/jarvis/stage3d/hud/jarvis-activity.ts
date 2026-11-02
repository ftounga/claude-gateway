import { JarvisUniverse, clientKey } from '../../scenes/jarvis-universe.models';

/**
 * F-236 / SF-236-26 — **la carte d'activité 7 jours** (NF-16). Fichier <b>pur</b>.
 *
 * <p>Où est passé le temps cette semaine : chaque client s'entoure d'un anneau à 7 segments (un par jour, du plus
 * ancien à aujourd'hui) dont l'éclat suit le nombre de tours du jour sur ses sujets. Source : l'agrégat en lecture
 * seule `GET /api/jarvis/activity?days=7` (compte de tours par sujet et par jour, filtré `user_id`, aucun
 * contenu).</p>
 */

/** Miroir de la réponse de la passerelle. */
export interface JarvisActivity {
  days: string[];
  subjects: { subjectId: string; turns: number[] }[];
}

/** L'anneau d'un client : ses tours par jour et l'éclat de chaque segment (0-1). */
export interface ActivityRing {
  clientKey: string;
  name: string;
  turns: number[];
  levels: number[];
  total: number;
}

export const ACTIVITY_DAYS = 7;
const SHORT_DAYS = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.'];

/** « lun. », « mar. »… pour chaque jour de la réponse (« 2026-10-27 »). */
export function dayLabels(activity: JarvisActivity | null): string[] {
  return (activity?.days ?? []).map((d) => {
    const [y, m, day] = d.split('-').map(Number);
    const date = new Date(y, (m ?? 1) - 1, day ?? 1);
    return Number.isNaN(date.getTime()) ? '' : SHORT_DAYS[date.getDay()];
  });
}

/**
 * Les anneaux des clients de l'univers : tours de leurs sujets additionnés jour par jour ; l'éclat est relatif au
 * jour le plus chargé de tous les clients (racine carrée : un jour à un tour se voit encore). Une réponse illisible
 * ⇒ aucun anneau. Les sujets inconnus de l'univers sont ignorés (rien n'est inventé).
 */
export function activityRings(activity: unknown, universe: JarvisUniverse | null): ActivityRing[] {
  const a = activity as JarvisActivity | null;
  if (!universe || !a || !Array.isArray(a.days) || !Array.isArray(a.subjects)) {
    return [];
  }
  const n = Math.min(ACTIVITY_DAYS, a.days.length);
  if (n === 0) {
    return [];
  }
  const bySubject = new Map<string, number[]>();
  for (const s of a.subjects) {
    if (s && typeof s.subjectId === 'string' && Array.isArray(s.turns)) {
      bySubject.set(s.subjectId, s.turns.slice(0, n).map((t) => (Number.isFinite(t) && t > 0 ? Math.floor(t) : 0)));
    }
  }
  const rings: ActivityRing[] = [];
  for (const c of universe.clients) {
    const turns = new Array<number>(n).fill(0);
    for (const s of c.subjects) {
      const per = bySubject.get(s.id);
      per?.forEach((t, i) => (turns[i] += t));
    }
    const total = turns.reduce((x, y) => x + y, 0);
    rings.push({ clientKey: clientKey(c), name: c.name, turns, levels: [], total });
  }
  const peak = Math.max(0, ...rings.flatMap((r) => r.turns));
  for (const r of rings) {
    r.levels = r.turns.map((t) => (peak === 0 ? 0 : Math.round(Math.sqrt(t / peak) * 100) / 100));
  }
  return rings;
}
