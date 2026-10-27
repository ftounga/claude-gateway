import { RunnerHostOverview } from '../core/models/atelier.models';
import { GovernanceIntegrite, GovernanceMap } from '../core/models/governance.models';

/**
 * **Les onglets du poste ouvert** (F-98 / SF-98-02, décision D3), en fonctions pures.
 *
 * <p>Onze blocs empilés deviennent quatre onglets, et chaque onglet porte <b>ce qui compte sans
 * l'ouvrir</b> : « 12 faits », « à appliquer ». On voit qu'il y a quelque chose, on clique si ça nous
 * regarde.</p>
 */

export type ForgeTab =
  'chantiers' | 'projets' | 'carte' | 'gouvernance' | 'activite' | 'pages' | 'videos' | 'presentations';

export const FORGE_TAB_LABELS: Record<ForgeTab, string> = {
  // F-220 / SF-220-03 : le tableau du client — ce qui reste à faire, qui le débloque. Onglet d'ouverture.
  chantiers: 'Chantiers',
  projets: 'Projets',
  carte: 'Carte',
  gouvernance: 'Gouvernance',
  activite: 'Activité',
  // F-109 / SF-109-04 : les pages publiées depuis les projets et le terminal de ce poste.
  pages: 'Pages',
  // F-213 / SF-213-03 : les vidéos publiées depuis les sujets de ce poste, séries rangées sous leur titre.
  videos: 'Vidéos',
  // F-129 / SF-129-02 : les présentations .pptx capturées depuis les projets de ce poste.
  presentations: 'Présentations',
};

const MACHINE_TABS: readonly ForgeTab[] =
  ['chantiers', 'projets', 'carte', 'gouvernance', 'activite', 'pages', 'videos', 'presentations'];

/**
 * Le poste « Hébergé » n'a pas de racine (F-71) : ni carte, ni gouvernance, ni activité de machine — et pas
 * de tableau des chantiers, qui se lit par poste (F-220).
 */
const HOSTED_TABS: readonly ForgeTab[] = ['projets'];

/** Les onglets qu'un poste propose. */
export function tabsFor(host: RunnerHostOverview): readonly ForgeTab[] {
  return host.virtual === true ? HOSTED_TABS : MACHINE_TABS;
}

/**
 * L'onglet d'ouverture d'un poste : le tableau des chantiers pour une machine (F-220, décision PO : « arriver
 * et retrouver ce qui me reste à faire »), Projets pour « Hébergé ».
 */
export function defaultTab(host: RunnerHostOverview): ForgeTab {
  return tabsFor(host)[0];
}

/** L'onglet réellement ouvert : celui demandé par `?onglet=` s'il existe pour ce poste, sinon l'onglet d'ouverture. */
export function effectiveTab(requested: string | null | undefined, host: RunnerHostOverview): ForgeTab {
  const wanted = (requested ?? '').trim().toLowerCase();
  return tabsFor(host).find((tab) => tab === wanted) ?? defaultTab(host);
}

/**
 * Le résumé de l'onglet Carte : « 12 faits » quand la carte est lue et gouvernée, « hors ligne »
 * quand le poste l'est (la carte n'a pas pu être lue), rien tant qu'on ne sait rien.
 */
export function mapTabSummary(map: GovernanceMap | null, online: boolean): string | null {
  if (!online) {
    return 'hors ligne';
  }
  if (!map || !map.governed || !map.readable) {
    return null;
  }
  return map.facts === 1 ? '1 fait' : `${map.facts} faits`;
}

export interface GovernanceTabSummary {
  label: string;
  /** Classe de pastille §5 : une mise à jour attend (`warning`), une erreur empêche (`error`). */
  tone: 'warning' | 'error';
}

/**
 * Le résumé de l'onglet Gouvernance : « à appliquer » si un paquet a une version plus récente (F-96),
 * sinon « à corriger » si l'intégrité relève des erreurs (F-95), sinon rien. Un avertissement seul ne
 * se résume pas : il est informatif, et une pastille permanente deviendrait invisible.
 */
export function governanceTabSummary(outdated: number,
  integrite: GovernanceIntegrite | null): GovernanceTabSummary | null {
  if (outdated > 0) {
    return { label: 'à appliquer', tone: 'warning' };
  }
  if (integrite?.inspected && integrite.errors.length > 0) {
    return { label: 'à corriger', tone: 'error' };
  }
  return null;
}
