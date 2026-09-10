/**
 * **État de mission d'un poste** (F-60 / SF-60-02) — *où en est-on*.
 *
 * <p>À ne pas confondre avec l'**identité visuelle** du poste (`shared/host-identity.ts`), qui
 * répond à *chez quel client suis-je*. Ce sont deux questions différentes, et c'est le piège
 * inscrit au cadrage de F-60 : deux systèmes de couleur qui se disputent la même surface
 * deviennent illisibles tous les deux.</p>
 *
 * <p><b>Deux registres séparés, non négociables</b> :</p>
 * <ul>
 *   <li>l'<b>identité</b> prend la palette dérivée du nom (`DESIGN_SYSTEM.md` §9) — dix tons, un
 *       filet, une pastille d'initiales ;</li>
 *   <li>l'<b>état de mission</b> prend la palette de <b>statut</b> (`DESIGN_SYSTEM.md` §5) — la
 *       même que l'utilisateur lit déjà comme « Connecté » ou « Actif », via les classes
 *       `.badge--success` / `.badge--warning` / `.badge--neutral` de la charte.</li>
 * </ul>
 *
 * <p>Aucune couleur n'est écrite ici : ce module ne rend que des <b>noms de classe</b> de la
 * charte. C'est ce qui garantit qu'aucune couleur nouvelle n'entre par cette porte, et que rien
 * de la palette d'identité ne vient qualifier un état.</p>
 *
 * <p><b>La couleur ne porte jamais seule l'information</b> : chaque état a un libellé écrit, et
 * `MissionBadgeComponent` n'a aucun mode qui l'omette.</p>
 */

/** Les trois états d'une mission. Miroir exact de l'énumération de la gateway. */
export type HostMissionStatus = 'ACTIVE' | 'PENDING' | 'CLOSED';

/** Ordre d'affichage et de choix : du plus vivant au plus rangé. */
export const MISSION_STATUSES: readonly HostMissionStatus[] = ['ACTIVE', 'PENDING', 'CLOSED'];

/**
 * État de repli. « En cours » et non « en attente » : un poste dont la gateway ne dit rien —
 * backend antérieur, champ absent — **est** une mission en cours. Le supposer en attente
 * inventerait un feu rouge que personne n'a posé, et le supposer clôturé le ferait disparaître de
 * la vue principale.
 */
export const DEFAULT_MISSION_STATUS: HostMissionStatus = 'ACTIVE';

/** Ce qui est **écrit** à côté de la couleur — jamais dérivé de la valeur brute de l'API. */
const MISSION_LABELS: Readonly<Record<HostMissionStatus, string>> = {
  ACTIVE: 'En cours',
  PENDING: 'En attente',
  CLOSED: 'Clôturé',
};

/**
 * Classe de pastille, prise **exclusivement** dans la charte (§5). Vert pour ce qui avance, ambre
 * pour ce qui attend, gris pour ce qui est rangé — les trois couleurs de statut que l'application
 * emploie déjà partout ailleurs.
 */
const MISSION_BADGE_CLASSES: Readonly<Record<HostMissionStatus, string>> = {
  ACTIVE: 'badge--success',
  PENDING: 'badge--warning',
  CLOSED: 'badge--neutral',
};

/** Icône Material accompagnant l'état. Décorative : le libellé, lui, est toujours écrit. */
const MISSION_ICONS: Readonly<Record<HostMissionStatus, string>> = {
  ACTIVE: 'play_circle',
  PENDING: 'pause_circle',
  CLOSED: 'check_circle',
};

/** Ce que l'entrée de menu explique, pour que le choix ne soit pas une devinette. */
const MISSION_HINTS: Readonly<Record<HostMissionStatus, string>> = {
  ACTIVE: 'La mission avance.',
  PENDING: 'La mission existe, elle attend un feu vert.',
  CLOSED: 'La mission est finie : le poste se range, rien n’est coupé.',
};

/**
 * Ramène une valeur venue de l'API à un état sûr.
 *
 * <p>Une valeur absente, nulle ou inconnue devient {@link DEFAULT_MISSION_STATUS} — jamais un
 * « inconnu » affiché, que la charte proscrit, et jamais la valeur brute d'une API recopiée à
 * l'écran.</p>
 */
export function normalizeMissionStatus(value: string | null | undefined): HostMissionStatus {
  return value === 'ACTIVE' || value === 'PENDING' || value === 'CLOSED'
    ? value
    : DEFAULT_MISSION_STATUS;
}

/** Libellé écrit de l'état. Jamais vide : c'est lui qui double la couleur. */
export function missionLabel(status: string | null | undefined): string {
  return MISSION_LABELS[normalizeMissionStatus(status)];
}

/** Classe de pastille de la charte (§5) correspondant à l'état. */
export function missionBadgeClass(status: string | null | undefined): string {
  return MISSION_BADGE_CLASSES[normalizeMissionStatus(status)];
}

/** Icône Material de l'état — décorative, elle ne remplace jamais le libellé. */
export function missionIcon(status: string | null | undefined): string {
  return MISSION_ICONS[normalizeMissionStatus(status)];
}

/** Phrase d'explication du choix, dans le menu de la vue d'ensemble. */
export function missionHint(status: string | null | undefined): string {
  return MISSION_HINTS[normalizeMissionStatus(status)];
}

/**
 * Vrai quand la mission est **clôturée** — le seul état qui range un poste hors de la vue
 * principale. Un poste clôturé ne disparaît pas : il descend dans un repli, avec ses projets et
 * son terminal intacts.
 */
export function isMissionClosed(status: string | null | undefined): boolean {
  return normalizeMissionStatus(status) === 'CLOSED';
}
