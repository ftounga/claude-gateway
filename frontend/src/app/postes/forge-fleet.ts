import { HostProjectSummary, RunnerHostOverview } from '../core/models/atelier.models';
import { isMissionClosed } from '../shared/mission-status';

/**
 * **La flotte de la Forge, en fonctions pures** (F-98 / SF-98-01).
 *
 * <p>La Forge refondue range les postes par <b>ce qu'ils demandent</b> (décision D4 du cadrage) :
 * d'abord ceux où une autorisation attend, puis ceux en ligne, puis les autres. Ces règles vivent
 * ici, sans Angular ni HTTP, parce qu'elles décident de <b>l'ordre de lecture de l'écran</b> — et
 * qu'une règle d'ordre se prouve mieux par des tests de fonction que par du DOM.</p>
 */

/** Référence du poste « Hébergé » dans l'URL : il n'a pas d'identifiant (F-71). */
export const HOSTED_REF = 'heberge';

/** Les groupes de la colonne, dans leur ordre d'affichage. */
export type ForgeGroupKey = 'attention' | 'online' | 'offline' | 'hosted' | 'closed';

export const FORGE_GROUP_LABELS: Record<ForgeGroupKey, string> = {
  attention: 'À regarder',
  online: 'En ligne',
  offline: 'Hors ligne',
  hosted: 'Sans machine',
  closed: 'Missions clôturées',
};

/** Une ligne de la colonne : le poste, et ce que la ligne en dit. */
export interface ForgeRow {
  ref: string;
  host: RunnerHostOverview;
  /** Autorisations en attente sur ce poste — projets et terminal du poste. */
  awaiting: number;
  /** Projets dont le nom correspond au filtre, ou `null` quand le poste correspond par son nom. */
  matchedProjects: number | null;
}

export interface ForgeGroup {
  key: ForgeGroupKey;
  label: string;
  rows: ForgeRow[];
}

/** Référence d'un poste dans l'URL : son identifiant, ou `heberge`. */
export function hostRef(host: RunnerHostOverview): string {
  return host.id ?? HOSTED_REF;
}

/** Vrai si ce projet attend une autorisation maintenant. */
export function projectAwaiting(project: HostProjectSummary): boolean {
  return project.terminalPreview?.activity === 'AWAITING_APPROVAL';
}

/** Nombre d'autorisations en attente sur un poste : ses projets **et** son terminal du poste. */
export function awaitingCount(host: RunnerHostOverview): number {
  const projects = (host.projects ?? []).filter(projectAwaiting).length;
  const hostTerminal = host.hostTerminalPreview?.activity === 'AWAITING_APPROVAL' ? 1 : 0;
  return projects + hostTerminal;
}

/** Normalise un texte pour la recherche : minuscules, sans accents, sans espaces bordants. */
export function normalizeSearch(value: string | null | undefined): string {
  return (value ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim();
}

/**
 * Ce que le filtre retient d'un poste : `'host'` s'il correspond par son nom, le nombre de projets
 * trouvés s'il ne correspond que par eux, `null` s'il ne correspond pas.
 */
export function filterMatch(host: RunnerHostOverview, filter: string): 'host' | number | null {
  const needle = normalizeSearch(filter);
  if (needle.length === 0 || normalizeSearch(host.name).includes(needle)) {
    return 'host';
  }
  const projects = (host.projects ?? [])
    .filter((project) => normalizeSearch(project.name).includes(needle)).length;
  return projects > 0 ? projects : null;
}

/**
 * Range les postes en groupes, dans l'ordre de la colonne. Un groupe vide n'est pas rendu, sauf
 * aucun : c'est l'écran qui décide de dire « rien ne correspond ».
 *
 * @param online état de ligne d'un poste — lu dans `HostPresenceService` (F-97), jamais recalculé ici.
 * @param awaitingOf ce qui attend sur un poste : les autorisations dans la Forge, les relances dues
 *        dans la Vigie (F-106 / SF-106-02).
 */
export function groupHosts(
  hosts: RunnerHostOverview[],
  online: (host: RunnerHostOverview) => boolean,
  filter: string,
  awaitingOf: (host: RunnerHostOverview) => number = awaitingCount,
): ForgeGroup[] {
  const buckets: Record<ForgeGroupKey, ForgeRow[]> = {
    attention: [], online: [], offline: [], hosted: [], closed: [],
  };
  for (const host of hosts) {
    const match = filterMatch(host, filter);
    if (match === null) {
      continue;
    }
    const row: ForgeRow = {
      ref: hostRef(host),
      host,
      awaiting: awaitingOf(host),
      matchedProjects: match === 'host' ? null : match,
    };
    buckets[groupOf(host, row.awaiting, online)].push(row);
  }
  return (Object.keys(buckets) as ForgeGroupKey[])
    .filter((key) => buckets[key].length > 0)
    .map((key) => ({ key, label: FORGE_GROUP_LABELS[key], rows: buckets[key] }));
}

function groupOf(host: RunnerHostOverview, awaiting: number,
  online: (host: RunnerHostOverview) => boolean): ForgeGroupKey {
  if (host.virtual === true) {
    // Ce qui attend passe devant, même sans machine : un projet hébergé peut attendre aussi.
    return awaiting > 0 ? 'attention' : 'hosted';
  }
  if (isMissionClosed(host.missionStatus)) {
    // Une mission close se range, même si quelque chose y attend : c'est le bandeau qui le compte.
    return 'closed';
  }
  if (awaiting > 0) {
    return 'attention';
  }
  return online(host) ? 'online' : 'offline';
}

/**
 * Le poste ouvert par `/forge` sans référence : le premier « À regarder », sinon le premier en
 * ligne, sinon le premier poste non clôturé, sinon « Hébergé ». Le filtre n'y entre pas : il ne
 * décide pas de ce qui est ouvert.
 */
export function defaultHostRef(
  hosts: RunnerHostOverview[],
  online: (host: RunnerHostOverview) => boolean,
  awaitingOf: (host: RunnerHostOverview) => number = awaitingCount,
): string {
  const groups = groupHosts(hosts, online, '', awaitingOf);
  for (const key of ['attention', 'online', 'offline'] as ForgeGroupKey[]) {
    const first = groups.find((group) => group.key === key)?.rows[0];
    if (first) {
      return first.ref;
    }
  }
  return HOSTED_REF;
}
