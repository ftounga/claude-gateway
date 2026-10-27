import { AutonomyRun } from './autonomy.models';

/**
 * **Le tableau de bord du client** (F-220) — le contrat de `GET /api/runner-hosts/{hostId}/chantiers`
 * (SF-220-02) et ses lectures pures. Aucun identifiant de compte : la gateway part du JWT.
 */

/** Qui doit agir pour qu'un chantier avance (SF-220-01). */
export type Unblocker = 'OUTIL' | 'MOI' | 'AUTRUI';

/** La marque « qui débloque », calculée par le serveur. */
export interface UnblockerMark {
  who: Unblocker;
  /** Libellé court de la pastille. */
  label: string;
  /** La phrase lisible telle quelle. */
  detail: string;
  /** Le rang de l'étape concernée, ou `null`. */
  step: number | null;
  /** À qui (seulement « quelqu'un »). */
  waitingOn: string | null;
  /** Depuis quand (une attente). */
  since: string | null;
}

/** La prochaine étape : la première ni faite ni vérifiée. */
export interface ClientBoardNextStep {
  rank: number;
  title: string;
}

/** Un chantier ouvert, sur sa carte. */
export interface ClientBoardChantier {
  workspaceId: string;
  workspaceName: string;
  hostTerminal: boolean;
  number: number;
  title: string | null;
  phase: string | null;
  phaseLabel: string | null;
  focus: boolean;
  openedAt: string | null;
  done: number;
  total: number;
  nextStep: ClientBoardNextStep | null;
  unblocker: UnblockerMark | null;
  /** Prêt à partir en autonome (préflight F-219). */
  ready: boolean;
  /** Ce qui l'empêche, lisible. */
  missing: string[];
  lastActivityAt: string | null;
  idleDays: number;
  dormant: boolean;
  /** Un tour vivant ou une course autonome en cours. */
  live: boolean;
  question: string | null;
  run: AutonomyRun | null;
}

/** Le tableau d'un client. */
export interface ClientBoard {
  hostId: string;
  hostName: string;
  generatedAt: string;
  dormantAfterDays: number;
  counts: { outil: number; moi: number; autrui: number; live: number; dormant: number };
  autonomy: { running: number; maxParallel: number };
  chantiers: ClientBoardChantier[];
}

/** Un filtre de l'en-tête. */
export type ClientBoardFilter = Unblocker | 'DORMANT';

/** L'ordre des groupes : ce que l'outil fait seul, puis ce que je débloque, puis ce que j'attends. */
export const UNBLOCKER_ORDER: readonly Unblocker[] = ['OUTIL', 'MOI', 'AUTRUI'];

/** Le titre de chaque groupe. */
export const UNBLOCKER_TITLES: Record<Unblocker, string> = {
  OUTIL: 'L\'outil peut avancer',
  MOI: 'Ça attend votre décision',
  AUTRUI: 'On attend quelqu\'un',
};

/** La phrase sous le titre du groupe. */
export const UNBLOCKER_HINTS: Record<Unblocker, string> = {
  OUTIL: 'Rien n\'attend personne : à faire avancer en premier, ou à lancer en autonome.',
  MOI: 'Une réponse, une validation ou un arbitrage — vous pouvez le débloquer tout de suite.',
  AUTRUI: 'Un message, une information, un accès ou la réponse d\'un tiers.',
};

/** L'icône Material de chaque couleur — décorative, le libellé l'accompagne toujours. */
export const UNBLOCKER_ICONS: Record<Unblocker, string> = {
  OUTIL: 'bolt',
  MOI: 'front_hand',
  AUTRUI: 'hourglass_top',
};

/** Le libellé court de la pastille, là où la place manque (onglets et étapes d'un sujet, SF-220-04). */
export const UNBLOCKER_SHORT: Record<Unblocker, string> = {
  OUTIL: 'l\'outil avance',
  MOI: 'votre décision',
  AUTRUI: 'on attend',
};

/** « dormant depuis 6 j », « actif aujourd'hui », « il y a 1 j ». */
export function idleLabel(chantier: Pick<ClientBoardChantier, 'idleDays' | 'dormant'>): string {
  if (chantier.dormant) {
    return `dormant depuis ${chantier.idleDays} j`;
  }
  return chantier.idleDays <= 0 ? 'actif aujourd\'hui' : `il y a ${chantier.idleDays} j`;
}

/** « depuis 3 j » / « depuis aujourd'hui » à partir d'une date ISO ; `null` sans date. */
export function sinceLabel(iso: string | null, now: Date = new Date()): string | null {
  if (!iso) {
    return null;
  }
  const at = new Date(iso).getTime();
  if (Number.isNaN(at)) {
    return null;
  }
  const days = Math.max(0, Math.floor((now.getTime() - at) / 86_400_000));
  return days === 0 ? 'depuis aujourd\'hui' : `depuis ${days} j`;
}

/** Le groupe d'un chantier ; un chantier sans marque va avec « quelqu'un » (rien d'exécutable connu). */
export function groupOf(chantier: ClientBoardChantier): Unblocker {
  return chantier.unblocker?.who ?? 'AUTRUI';
}

/** Vrai si le chantier passe le filtre. */
export function matchesFilter(chantier: ClientBoardChantier, filter: ClientBoardFilter | null): boolean {
  if (filter === null) {
    return true;
  }
  return filter === 'DORMANT' ? chantier.dormant : groupOf(chantier) === filter;
}

/** Une course autonome tourne sur ce chantier. */
export function isRunning(chantier: ClientBoardChantier): boolean {
  return chantier.run?.status === 'RUNNING';
}

/** Sélectionnable pour un lancement groupé : prêt et rien ne tourne. */
export function isSelectable(chantier: ClientBoardChantier): boolean {
  return chantier.ready && !isRunning(chantier);
}

/** La clé d'un chantier : sujet + numéro. */
export function chantierKey(chantier: Pick<ClientBoardChantier, 'workspaceId' | 'number'>): string {
  return `${chantier.workspaceId}#${chantier.number}`;
}

/** Les places libres pour des courses autonomes (F-219 : 4 par compte). */
export function freeSlots(board: Pick<ClientBoard, 'autonomy'>): number {
  return Math.max(0, board.autonomy.maxParallel - board.autonomy.running);
}

/** Le refus écrit quand la sélection dépasse les places libres ; `null` si elle tient. */
export function overLimitMessage(selected: number, board: Pick<ClientBoard, 'autonomy'>): string | null {
  const free = freeSlots(board);
  if (selected <= free) {
    return null;
  }
  const max = board.autonomy.maxParallel === 4 ? 'Quatre' : String(board.autonomy.maxParallel);
  const running = board.autonomy.running;
  return `${max} chantiers autonomes au maximum : ${running} en cours, `
    + (free === 0 ? 'aucune place libre.' : `${free} place${free > 1 ? 's' : ''} libre${free > 1 ? 's' : ''}.`);
}

/** Le bilan d'un lancement groupé, en une phrase. */
export function launchSummary(launched: number, refusals: string[]): string {
  const ok = launched === 0 ? 'Aucun chantier lancé' : `${launched} chantier${launched > 1 ? 's' : ''} lancé${launched > 1 ? 's' : ''} en autonome`;
  if (refusals.length === 0) {
    return `${ok}.`;
  }
  return `${ok} ; ${refusals.length} refusé${refusals.length > 1 ? 's' : ''} : ${refusals.join(' ; ')}`;
}
