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
  /** Reporté jusqu'à cette date (SF-220-05), ou `null` — absent d'une gateway antérieure. */
  snoozedUntil?: string | null;
  /** Peut « avancer jusqu'au blocage » (SF-219-05) — absent d'une gateway antérieure. */
  canAdvance?: boolean;
}

/** Le report d'un chantier (SF-220-05). */
export interface ChantierSnooze {
  number: number;
  until: string;
}

/**
 * Une attente ouverte hors de tout chantier (SF-220-07) : ce qu'aucun chantier ouvert ne montre déjà.
 * `unblocker.who` vaut `MOI` (un geste à moi, ou une demande pas encore partie) ou `AUTRUI` (demandée).
 */
export interface ClientBoardLooseTask {
  actionId: string;
  workspaceId: string;
  workspaceName: string;
  hostTerminal: boolean;
  description: string;
  blocks: string | null;
  /** `A_FAIRE` ou `DEMANDE`. */
  status: 'A_FAIRE' | 'DEMANDE';
  qui: 'MOI' | 'CLIENT' | 'TIERS' | null;
  categorie: string | null;
  person: string | null;
  requestedTo: string | null;
  requestedAt: string | null;
  createdAt: string | null;
  unblocker: UnblockerMark;
  lastActivityAt: string | null;
  idleDays: number;
  dormant: boolean;
  /** Demandée depuis assez longtemps pour relancer (F-175). */
  followUpDue: boolean;
}

/** Le tableau d'un client. */
export interface ClientBoard {
  hostId: string;
  hostName: string;
  generatedAt: string;
  dormantAfterDays: number;
  /** `moi`, `autrui` et `dormant` comptent aussi les attentes hors chantier (SF-220-07). */
  counts: {
    outil: number; moi: number; autrui: number; live: number; dormant: number; snoozed?: number;
    horsChantier?: number;
  };
  autonomy: { running: number; maxParallel: number };
  chantiers: ClientBoardChantier[];
  /** Les attentes hors chantier (SF-220-07) — absent d'une gateway antérieure. */
  horsChantier?: ClientBoardLooseTask[];
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

/** Reporté (SF-220-05) : rangé à part, hors des groupes et de la sélection. */
export function isSnoozed(chantier: Pick<ClientBoardChantier, 'snoozedUntil'>): boolean {
  return !!chantier.snoozedUntil;
}

/** Peut « avancer jusqu'au blocage » (SF-219-05) : un chantier prêt le peut toujours. */
export function canAdvance(chantier: Pick<ClientBoardChantier, 'ready' | 'canAdvance'>): boolean {
  return chantier.ready || !!chantier.canAdvance;
}

/**
 * Sélectionnable pour un lancement groupé : prêt ou capable d'avancer jusqu'au blocage (SF-219-05), rien ne
 * tourne, pas reporté.
 */
export function isSelectable(chantier: ClientBoardChantier): boolean {
  return canAdvance(chantier) && !isRunning(chantier) && !isSnoozed(chantier);
}

/** « 30/10 » — la date de fin d'un report, au format court. */
export function untilLabel(iso: string | null | undefined): string {
  if (!iso) {
    return '';
  }
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) {
    return '';
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(at.getDate())}/${pad(at.getMonth() + 1)}`;
}

/** Les durées de report proposées (SF-220-06). */
export const SNOOZE_CHOICES: readonly { days: number; label: string }[] = [
  { days: 1, label: 'Reporter d\'un jour' },
  { days: 3, label: 'Reporter de 3 jours' },
  { days: 7, label: 'Reporter d\'une semaine' },
];

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

/** Un sous-groupe de la section « Hors chantier » : sa couleur et ses attentes. */
export interface LooseTaskGroup {
  who: Unblocker;
  tasks: ClientBoardLooseTask[];
}

/**
 * Les sous-groupes non vides de « Hors chantier », dans l'ordre du tableau (ma décision, puis quelqu'un),
 * filtre de l'en-tête appliqué (« L'outil peut avancer » ne garde aucune attente).
 */
export function looseTaskGroups(tasks: readonly ClientBoardLooseTask[] | null | undefined,
                                filter: ClientBoardFilter | null): LooseTaskGroup[] {
  const all = tasks ?? [];
  return UNBLOCKER_ORDER
    .map((who) => ({
      who,
      tasks: all.filter((t) => t.unblocker.who === who
        && (filter === null || (filter === 'DORMANT' ? t.dormant : t.unblocker.who === filter))),
    }))
    .filter((group) => group.tasks.length > 0);
}

/** [Marquer demandé] n'a de sens que pour une demande pas encore partie, envers quelqu'un d'autre. */
export function canMarkRequested(task: Pick<ClientBoardLooseTask, 'status' | 'qui'>): boolean {
  return task.status === 'A_FAIRE' && task.qui !== 'MOI';
}

/**
 * Un bloc de sujet (SF-220-08) : le tableau du client est rangé par sujet. Les compteurs et l'ancienneté sont
 * ceux du sujet **sans filtre** ; les listes sont filtrées.
 */
export interface SubjectBlock {
  workspaceId: string;
  /** Le nom affiché (« Terminal du poste » pour le terminal du poste). */
  name: string;
  hostTerminal: boolean;
  /** Chantiers non reportés et attentes hors chantier, par couleur, sans filtre. */
  counts: Record<Unblocker, number>;
  /** Au moins un élément attend la décision de l'utilisateur. */
  hasDecision: boolean;
  /** L'élément le plus dormant du sujet (chantier ou attente). */
  oldest: { idleDays: number; dormant: boolean };
  /** Les chantiers du sujet, outil → décision → quelqu'un (ordre du serveur dans chaque couleur), filtrés. */
  chantiers: ClientBoardChantier[];
  /** Les attentes hors chantier du sujet, décision puis quelqu'un, filtrées. */
  tasks: ClientBoardLooseTask[];
}

/**
 * Range le tableau par sujet (SF-220-08). Un sujet n'a de bloc que s'il a un chantier non reporté ou une attente
 * hors chantier. Ordre des sujets, calculé **sans filtre** (les blocs ne sautent pas quand on filtre) :
 * 1. ceux qui ont un élément « votre décision » ; 2. le plus dormant d'abord ; 3. le nom.
 * Le filtre s'applique dans les blocs ; un bloc vidé par le filtre n'est pas rendu.
 */
export function subjectBlocks(board: Pick<ClientBoard, 'chantiers' | 'horsChantier'> | null,
                              filter: ClientBoardFilter | null): SubjectBlock[] {
  if (!board) {
    return [];
  }
  const open = board.chantiers.filter((c) => !isSnoozed(c));
  const tasks = board.horsChantier ?? [];
  const byId = new Map<string, { block: SubjectBlock; allChantiers: ClientBoardChantier[]; allTasks: ClientBoardLooseTask[] }>();
  const entry = (item: { workspaceId: string; workspaceName: string; hostTerminal: boolean }) => {
    let found = byId.get(item.workspaceId);
    if (!found) {
      found = {
        block: {
          workspaceId: item.workspaceId,
          name: item.hostTerminal ? 'Terminal du poste' : item.workspaceName,
          hostTerminal: item.hostTerminal,
          counts: { OUTIL: 0, MOI: 0, AUTRUI: 0 },
          hasDecision: false,
          oldest: { idleDays: -1, dormant: false },
          chantiers: [],
          tasks: [],
        },
        allChantiers: [],
        allTasks: [],
      };
      byId.set(item.workspaceId, found);
    }
    return found;
  };
  const note = (block: SubjectBlock, who: Unblocker, idle: { idleDays: number; dormant: boolean }) => {
    block.counts[who] += 1;
    if (idle.idleDays > block.oldest.idleDays || (idle.idleDays === block.oldest.idleDays && idle.dormant)) {
      block.oldest = { idleDays: idle.idleDays, dormant: idle.dormant };
    }
  };
  for (const c of open) {
    const e = entry(c);
    e.allChantiers.push(c);
    note(e.block, groupOf(c), c);
  }
  for (const t of tasks) {
    const e = entry(t);
    e.allTasks.push(t);
    note(e.block, t.unblocker.who, t);
  }
  const blocks: SubjectBlock[] = [];
  for (const { block, allChantiers, allTasks } of byId.values()) {
    block.hasDecision = block.counts.MOI > 0;
    block.oldest = { idleDays: Math.max(0, block.oldest.idleDays), dormant: block.oldest.dormant };
    block.chantiers = UNBLOCKER_ORDER.flatMap((who) =>
      allChantiers.filter((c) => groupOf(c) === who && matchesFilter(c, filter)));
    block.tasks = looseTaskGroups(allTasks, filter).flatMap((group) => group.tasks);
    blocks.push(block);
  }
  return blocks
    .sort((a, b) => (Number(b.hasDecision) - Number(a.hasDecision))
      || (b.oldest.idleDays - a.oldest.idleDays)
      || a.name.localeCompare(b.name, 'fr'))
    .filter((block) => block.chantiers.length > 0 || block.tasks.length > 0);
}
