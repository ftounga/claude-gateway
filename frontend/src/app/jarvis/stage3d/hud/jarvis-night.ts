import { JarvisWatchSummary } from '../../../core/models/veille.models';
import type { Ambient } from '../../scenes/jarvis-scene-bus.service';

/**
 * F-236 / SF-236-24 — **le mode veilleur de nuit** (NF-13). Fichier <b>pur</b>.
 *
 * <p>Écran posé (cockpit, tablette) : luminosité basse, horloge, prochaines veilles et alertes seulement. Une alerte
 * qui <b>arrive</b> (poste muet, autre sujet qui appelle, veille qui sonne) réveille l'écran {@link NIGHT_AWAKE_MS} ;
 * un toucher aussi.</p>
 */

/** Durée du réveil après une alerte ou un toucher. */
export const NIGHT_AWAKE_MS = 60_000;
/** Au plus autant de prochaines veilles affichées. */
export const NIGHT_WATCHES = 3;

/** Une alerte telle que le veilleur l'écrit. */
export interface NightAlert {
  key: string;
  text: string;
}

/** Les alertes du moment, lues dans l'ambiance (aucun appel). */
export function nightAlerts(ambient: Ambient | null): NightAlert[] {
  const out: NightAlert[] = [];
  if (ambient?.alert) {
    out.push({ key: `alert:${ambient.alert.clientKey ?? ''}:${ambient.alert.label}`,
      text: ambient.alert.label || 'Une veille a sonné' });
  }
  if (ambient?.hostDown) {
    out.push({ key: `host:${ambient.hostDown.clientKey}`, text: `Poste muet : ${ambient.hostDown.name}` });
  }
  if (ambient?.calling) {
    out.push({ key: `call:${ambient.calling.subjectId}`, text: ambient.calling.label || 'Un sujet vous appelle' });
  }
  return out;
}

/** Une prochaine veille : son nom, son sujet, l'heure du passage. */
export interface NightWatch {
  id: string;
  name: string;
  subject: string;
  at: number;
  /** « 07:30 » ou « lun. 07:30 » si ce n'est pas aujourd'hui. */
  when: string;
}

const DAYS = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.'];

function hhmm(d: Date): string {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** Les prochaines veilles actives (passage connu du serveur), de la plus proche à la plus lointaine. */
export function nextWatches(list: readonly JarvisWatchSummary[] | null, now: Date): NightWatch[] {
  const out: NightWatch[] = [];
  for (const item of list ?? []) {
    const w = item?.watch;
    const at = w?.nextRunAt ? Date.parse(w.nextRunAt) : NaN;
    if (!w || !w.active || w.proposed || Number.isNaN(at) || at < now.getTime()) {
      continue;
    }
    const d = new Date(at);
    const sameDay = d.toDateString() === now.toDateString();
    out.push({ id: w.id, name: w.name, subject: item.workspaceName ?? '', at,
      when: sameDay ? hhmm(d) : `${DAYS[d.getDay()]} ${hhmm(d)}` });
  }
  return out.sort((a, b) => a.at - b.at).slice(0, NIGHT_WATCHES);
}

/** Le réveil du veilleur : une alerte nouvelle (clé jamais vue) ou un toucher le tient éveillé un moment. */
export class NightWake {
  private seen = new Set<string>();
  private until = 0;

  /** Relit les alertes ; `true` si une alerte nouvelle vient de réveiller l'écran. */
  observe(alerts: readonly NightAlert[], now: number): boolean {
    let woke = false;
    const keys = new Set(alerts.map((a) => a.key));
    for (const k of keys) {
      if (!this.seen.has(k)) {
        woke = true;
      }
    }
    this.seen = keys;
    if (woke) {
      this.until = now + NIGHT_AWAKE_MS;
    }
    return woke;
  }

  touch(now: number): void {
    this.until = now + NIGHT_AWAKE_MS;
  }

  awake(now: number): boolean {
    return now < this.until;
  }
}
