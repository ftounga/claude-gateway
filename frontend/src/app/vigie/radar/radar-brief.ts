import { RadarCoverageItem, RadarSyncView } from '../../core/models/radar.models';

/**
 * **Le résumé du matin, en fonctions pures** (F-102 / SF-102-01) : ses titres et ses mots. Sans Angular
 * ni HTTP, comme `vigie-fleet.ts`.
 */

/** « Mardi 15 septembre » : le jour du résumé. */
export function briefDay(now: Date = new Date()): string {
  const text = now.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * « Synchro d'hier, 22 h 00 — ce qui a été lu » : l'en-tête de la couverture.
 *
 * @param now instant de référence (injecté pour les tests)
 */
export function coverageHeading(sync: RadarSyncView | null, now: Date = new Date()): string {
  if (!sync) {
    return 'Aucune synchro encore';
  }
  const instant = sync.startedAt ?? sync.finishedAt;
  const date = instant ? new Date(instant) : null;
  if (!date || Number.isNaN(date.getTime())) {
    return 'Dernière synchro — ce qui a été lu';
  }
  const startOf = (value: Date) => new Date(value.getFullYear(), value.getMonth(), value.getDate()).getTime();
  const days = Math.round((startOf(now) - startOf(date)) / 86_400_000);
  const day = days === 0 ? "d'aujourd'hui" : days === 1 ? "d'hier"
    : `du ${date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long' })}`;
  const hour = `${date.getHours()} h ${String(date.getMinutes()).padStart(2, '0')}`;
  return `Synchro ${day}, ${hour} — ce qui a été lu`;
}

/** Le manque en mots courts : « lu en partie », « sans transcription »… */
export function coverageStatusLabel(status: string): string {
  switch (status) {
    case 'PARTIAL': return 'lu en partie';
    case 'UNREAD_CHANNEL': return 'canal non lu';
    case 'NO_TRANSCRIPT': return 'sans transcription';
    case 'DENIED': return 'transcription refusée';
    case 'UNAVAILABLE': return 'non transcrit';
    case 'DATE_GUESSED': return 'date devinée';
    default: return 'non lu';
  }
}

/** L'icône Material d'un manque, selon ce qu'il est. */
export function coverageKindIcon(kind: string): string {
  switch (kind) {
    case 'CHANNEL': return 'tag';
    case 'MEETING': return 'videocam';
    case 'RECORDING': return 'mic';
    default: return 'forum';
  }
}

/** L'icône Material d'une source lue par la synchro. */
export function coverageSourceIcon(source: string): string {
  switch (source) {
    case 'MEETINGS': return 'videocam';
    case 'RECORDINGS': return 'mic';
    default: return 'forum';
  }
}

/** Le libellé d'une règle posée. */
export function ruleLabel(rule: string | null): string | null {
  return rule === 'IGNORE' ? 'ignoré' : rule === 'READ_CHANNEL' ? 'sera lu' : null;
}

/** Les gestes d'un manque, dans l'ordre d'affichage — aucun si une règle est déjà posée. */
export function itemGestures(item: RadarCoverageItem): ('READ_CHANNEL' | 'IGNORE')[] {
  if (item.rule) {
    return [];
  }
  const out: ('READ_CHANNEL' | 'IGNORE')[] = [];
  if (item.actions.includes('READ_CHANNEL')) {
    out.push('READ_CHANNEL');
  }
  if (item.actions.includes('IGNORE')) {
    out.push('IGNORE');
  }
  return out;
}
