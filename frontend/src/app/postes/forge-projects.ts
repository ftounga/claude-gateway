import { HostProjectSummary } from '../core/models/atelier.models';
import { normalizeSearch, projectAwaiting } from './forge-fleet';

/**
 * **Les projets du poste ouvert, en grille** (F-98 / SF-98-03), en fonctions pures : l'ordre des
 * tuiles et ce que chacune montre en son centre.
 */

export type ForgeProjectSort = 'actifs' | 'alpha' | 'recents';

export const FORGE_PROJECT_SORTS: readonly { key: ForgeProjectSort; label: string }[] = [
  { key: 'actifs', label: 'Actifs d’abord' },
  { key: 'alpha', label: 'A → Z' },
  { key: 'recents', label: 'Récents' },
];

/** Ce qu'une tuile montre en son centre — **un seul** contenu, par ordre de priorité. */
export type ForgeTileCenter = 'awaiting' | 'preview' | 'idle';

/** Vrai si l'aperçu a quelque chose à dire : une activité autre que le repos, ou des lignes. */
function previewSpeaks(project: HostProjectSummary): boolean {
  const preview = project.terminalPreview;
  return !!preview && (preview.activity !== 'IDLE' || (preview.lines?.length ?? 0) > 0);
}

/** Autorisation en attente › aperçu des dernières lignes › au repos. */
export function tileCenter(project: HostProjectSummary): ForgeTileCenter {
  if (projectAwaiting(project)) {
    return 'awaiting';
  }
  return previewSpeaks(project) ? 'preview' : 'idle';
}

/** Instant de dernière activité en millisecondes ; illisible ou absent ⇒ `null`. */
function activityMs(project: HostProjectSummary): number | null {
  if (!project.lastActivityAt) {
    return null;
  }
  const at = new Date(project.lastActivityAt).getTime();
  return Number.isFinite(at) ? at : null;
}

const collator = new Intl.Collator('fr', { sensitivity: 'base' });

function byName(a: HostProjectSummary, b: HostProjectSummary): number {
  return collator.compare(a.name ?? '', b.name ?? '');
}

/** Le plus récent d'abord ; sans activité connue, à la fin. */
function byRecent(a: HostProjectSummary, b: HostProjectSummary): number {
  const left = activityMs(a);
  const right = activityMs(b);
  if (left === right) {
    return 0;
  }
  if (left === null) {
    return 1;
  }
  if (right === null) {
    return -1;
  }
  return right - left;
}

/** Rang d'attention : ce qui attend (0), ce qui travaille ou vit (1), le reste (2). */
function attentionRank(project: HostProjectSummary): number {
  if (projectAwaiting(project)) {
    return 0;
  }
  return project.active || project.liveTerminal ? 1 : 2;
}

/** Trie les projets sans modifier la liste reçue. */
export function sortProjects(projects: HostProjectSummary[], sort: ForgeProjectSort): HostProjectSummary[] {
  const copy = [...projects];
  switch (sort) {
    case 'alpha':
      return copy.sort(byName);
    case 'recents':
      return copy.sort((a, b) => byRecent(a, b) || byName(a, b));
    default:
      return copy.sort((a, b) => attentionRank(a) - attentionRank(b) || byRecent(a, b) || byName(a, b));
  }
}

/** Les projets dont le nom correspond au filtre ; filtre vide ⇒ tous. */
export function filterProjects(projects: HostProjectSummary[], filter: string): HostProjectSummary[] {
  const needle = normalizeSearch(filter);
  if (needle.length === 0) {
    return projects;
  }
  return projects.filter((project) => normalizeSearch(project.name).includes(needle));
}
