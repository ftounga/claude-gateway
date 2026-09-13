import { HostMissionStatus } from '../core/models/atelier.models';
import { HostSpaces, VigieRadarCounts, VigieSyncSummary } from '../core/models/vigie.models';
import { isMissionClosed } from '../shared/mission-status';

/**
 * **La Vigie, en fonctions pures** (F-106 / SF-106-02) : ses onglets, son bandeau, et la liste
 * d'où l'on active un client de la Forge. Sans Angular ni HTTP, comme `forge-fleet.ts`.
 */

/** Les onglets d'un client dans la Vigie, dans leur ordre (cadrage §4). */
export type VigieTab = 'radar' | 'conversations' | 'reunions' | 'personnes' | 'pages';

export const VIGIE_TABS: readonly VigieTab[] = ['radar', 'conversations', 'reunions', 'personnes', 'pages'];

export const VIGIE_TAB_LABELS: Record<VigieTab, string> = {
  radar: 'Radar',
  conversations: 'Conversations',
  reunions: 'Réunions',
  personnes: 'Personnes',
  // F-109 / SF-109-04 : les pages publiées depuis le terminal Teams de ce client.
  pages: 'Pages',
};

/** L'onglet ouvert : `?onglet=` s'il en nomme un, sinon le Radar. */
export function effectiveVigieTab(raw: string | null | undefined): VigieTab {
  const wanted = (raw ?? '').trim().toLowerCase();
  return (VIGIE_TABS as readonly string[]).includes(wanted) ? (wanted as VigieTab) : 'radar';
}

/** Ce que dit le bandeau de la Vigie, tous clients confondus. */
export interface VigieFleetSummary {
  followUpsDue: number;
  blockedSubjects: number;
  /** Ce qui réclame un geste, tous clients confondus (F-102 / SF-102-03). */
  toHandle: number;
  /** La synchro la plus récente de la flotte, ou `null` s'il n'y en a jamais eu. */
  lastSync: VigieSyncSummary | null;
}

/** Additionne les compteurs du Radar de chaque client. */
export function fleetSummary(counts: Record<string, VigieRadarCounts>): VigieFleetSummary {
  let followUpsDue = 0;
  let blockedSubjects = 0;
  let toHandle = 0;
  let lastSync: VigieSyncSummary | null = null;
  for (const entry of Object.values(counts)) {
    followUpsDue += entry.followUpsDue;
    blockedSubjects += entry.blockedSubjects;
    toHandle += entry.toHandle ?? 0;
    if (entry.lastSync && (!lastSync
      || (entry.lastSync.startedAt ?? '').localeCompare(lastSync.startedAt ?? '') > 0)) {
      lastSync = entry.lastSync;
    }
  }
  return { followUpsDue, blockedSubjects, toHandle, lastSync };
}

/** « 5 à traiter » : la pastille de l'onglet Radar et le fait du bandeau (F-102 / SF-102-03). */
export function toHandleLabel(count: number): string {
  return `${count} à traiter`;
}

/**
 * La synchro, dite en mots : « synchro d'hier soir complète », « synchro en cours », « dernière
 * synchro incomplète », « aucune synchro encore ».
 *
 * @param now instant de référence (injecté pour les tests)
 */
export function syncLabel(sync: VigieSyncSummary | null, now: Date = new Date()): string {
  if (!sync) {
    return 'aucune synchro encore';
  }
  if (sync.status === 'RUNNING') {
    return 'synchro en cours';
  }
  const when = dayWord(sync.finishedAt ?? sync.startedAt, now);
  const outcome = sync.status === 'SUCCEEDED' ? 'complète'
    : sync.status === 'PARTIAL' ? 'incomplète'
      : sync.status === 'CANCELLED' ? 'interrompue' : 'en échec';
  return when ? `synchro ${when} ${outcome}` : `dernière synchro ${outcome}`;
}

/** Vrai si la synchro appelle un regard : incomplète ou en échec. */
export function syncNeedsAttention(sync: VigieSyncSummary | null): boolean {
  return sync !== null && (sync.status === 'PARTIAL' || sync.status === 'FAILED');
}

function dayWord(instant: string | null, now: Date): string | null {
  if (!instant) {
    return null;
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  const startOf = (value: Date) => new Date(value.getFullYear(), value.getMonth(), value.getDate()).getTime();
  const days = Math.round((startOf(now) - startOf(date)) / 86_400_000);
  const evening = date.getHours() >= 18;
  if (days === 0) {
    return evening ? 'de ce soir' : "d'aujourd'hui";
  }
  if (days === 1) {
    return evening ? "d'hier soir" : "d'hier";
  }
  return `du ${date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long' })}`;
}

/**
 * Les clients de la Forge qu'on peut activer dans la Vigie : ceux qui n'y sont pas encore, les
 * missions en cours d'abord, puis par nom.
 */
export function importableHosts(hosts: HostSpaces[]): HostSpaces[] {
  const rank = (status: HostMissionStatus) => (isMissionClosed(status) ? 1 : 0);
  return hosts
    .filter((host) => !host.spaces.includes('VIGIE'))
    .sort((a, b) => rank(a.missionStatus) - rank(b.missionStatus)
      || a.name.localeCompare(b.name, 'fr', { sensitivity: 'base' }));
}

/** « 2 relances » / « 1 relance » : le libellé de la pastille d'attente dans la colonne de la Vigie. */
export function followUpLabel(count: number): string {
  return count === 1 ? '1 relance' : `${count} relances`;
}
