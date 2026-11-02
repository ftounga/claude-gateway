import { JarvisUniverse, NodeRef, clientKey } from '../../scenes/jarvis-universe.models';
import type { Ambient } from '../../scenes/jarvis-scene-bus.service';
import { ShowFilter } from '../../scenes/jarvis-show';
import { ChantierWeather, WEATHER_RANK, weatherOf } from './jarvis-weather';

/**
 * F-236 / SF-236-16 — **le tableau de bord de la journée** (NF-1, « Aujourd'hui ») et la **liste météo** (NF-2).
 * Fichier <b>pur</b> : chaque chiffre est compté dans l'univers déjà lu (et l'ambiance hors tour) ; toucher un
 * chiffre applique localement le filtre « Montrer » correspondant — <b>sans tour</b>, sans appel.
 */
export type DayFigureId = 'postes' | 'chantiers' | 'attentes' | 'a-envoyer' | 'alertes';

export interface DayFigure {
  id: DayFigureId;
  label: string;
  /** Ce qui s'écrit (« 1/2 », « 3 »). */
  value: string;
  /** Le filtre « Montrer » appliqué au toucher ; `null` ⇒ rien à montrer. */
  filter: ShowFilter | null;
  /** La teinte (grammaire §23) ; le mot écrit porte l'information. */
  tone: 'normal' | 'green' | 'wait' | 'alert' | 'off';
}

function isClosed(ch: { phase: string | null; done: number; total: number }): boolean {
  return ch.phase === 'CLOS' || (ch.total > 0 && ch.done >= ch.total);
}

/** Les chiffres du jour ; `toSendAdded` = messages finis pendant le tour (comme le compteur du HUD). */
export function dayBoard(universe: JarvisUniverse | null, ambient: Ambient | null, toSendAdded = 0): DayFigure[] {
  if (!universe) {
    return [];
  }
  const machines = universe.clients.filter((c) => !c.virtual);
  const connected = machines.filter((c) => c.connected).length;
  let open = 0;
  let waiting = 0;
  for (const c of universe.clients) {
    for (const s of c.subjects) {
      for (const ch of s.chantiers) {
        if (isClosed(ch)) {
          continue;
        }
        open++;
        if (ch.waitingOn.length > 0) {
          waiting++;
        }
      }
    }
  }
  const toSend = universe.toSendCount + toSendAdded;
  const alerts = (ambient?.hostDown ? 1 : 0) + (ambient?.alert ? 1 : 0);
  return [
    { id: 'postes', label: 'Postes connectés', value: `${connected}/${machines.length}`,
      filter: machines.length > 0 ? 'connectes' : null, tone: connected > 0 ? 'green' : 'off' },
    { id: 'chantiers', label: 'Chantiers en cours', value: `${open}`, filter: open > 0 ? 'etapes-en-cours' : null,
      tone: 'normal' },
    { id: 'attentes', label: 'Attentes', value: `${waiting}`, filter: waiting > 0 ? 'chantiers-bloques' : null,
      tone: waiting > 0 ? 'wait' : 'off' },
    { id: 'a-envoyer', label: 'À envoyer', value: `${toSend}`, filter: toSend > 0 ? 'a-envoyer' : null,
      tone: toSend > 0 ? 'wait' : 'off' },
    { id: 'alertes', label: 'Alertes', value: `${alerts}`, filter: ambient?.hostDown ? 'deconnectes' : null,
      tone: alerts > 0 ? 'alert' : 'off' },
  ];
}

/** Une ligne de la liste météo : le chantier (nœud de l'univers) et son ciel. */
export interface WeatherRow extends ChantierWeather {
  node: NodeRef;
  /** « CAGIP · Migration Forge ». */
  where: string;
}

/** Tous les chantiers ouverts et clos de l'univers, du plus grave au plus serein (puis par nom). */
export function weatherRows(universe: JarvisUniverse | null): WeatherRow[] {
  const rows: WeatherRow[] = [];
  for (const c of universe?.clients ?? []) {
    for (const s of c.subjects) {
      for (const ch of s.chantiers) {
        rows.push({
          ...weatherOf(ch),
          node: { kind: 'chantier', key: `${s.id}#${ch.number}`, label: `Chantier ${ch.number} — ${ch.title}`,
            clientKey: clientKey(c), subjectId: s.id },
          where: `${c.name} · ${s.name}`,
        });
      }
    }
  }
  return rows.sort((a, b) => WEATHER_RANK[a.weather] - WEATHER_RANK[b.weather]
    || a.node.label.localeCompare(b.node.label));
}
