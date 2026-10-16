/**
 * **Les attentes d'un terminal** (F-154, F-175) : ce que l'utilisateur doit faire, lui, pour qu'un
 * tour bloqué reprenne — et où en est la demande.
 */

/** La nature du geste attendu : un message à écrire, ou autre chose à faire. */
export type TerminalActionKind = 'ACTION' | 'MESSAGE';

/** À faire → Demandé → Fait, et Annulé en sortie latérale (F-175 / SF-175-01). */
export type TerminalActionStatus = 'A_FAIRE' | 'DEMANDE' | 'FAIT' | 'ANNULE';

/** Les libellés affichés, uniques pour tout l'écran. */
export const TERMINAL_ACTION_STATUS_LABELS: Record<TerminalActionStatus, string> = {
  A_FAIRE: 'À faire',
  DEMANDE: 'Demandé',
  FAIT: 'Fait',
  ANNULE: 'Annulé',
};

/** Vrai tant que l'attente attend encore quelque chose. */
export function isOpenStatus(status: TerminalActionStatus): boolean {
  return status === 'A_FAIRE' || status === 'DEMANDE';
}

/** Une attente (`GET /api/workspaces/{id}/actions`, et le tableau). */
export interface TerminalAction {
  id: string;
  /** L'attente gardée quand celle-ci a été fusionnée (F-180 / SF-180-01). */
  mergedIntoId?: string | null;
  workspaceId: string;
  /** Le nom du terminal d'origine — rempli dans le tableau du poste seulement. */
  workspaceName?: string | null;
  hostId?: string | null;
  subjectId: string | null;
  description: string;
  /** Ce que l'action débloque — la raison qui la rend urgente. */
  blocks: string | null;
  person: string | null;
  kind: TerminalActionKind;
  status: TerminalActionStatus;
  /** Quand la demande est partie, à qui, par où (état « Demandé »). */
  requestedAt?: string | null;
  requestedTo?: string | null;
  channel?: string | null;
  /** La phrase qui a fermé l'action : sans elle, on ne saurait plus pourquoi elle a disparu. */
  closedReason: string | null;
  closedAt: string | null;
  /**
   * La fermeture **proposée** par l'agent (F-175 / SF-175-02) : l'attente reste ouverte tant que
   * l'utilisateur n'a pas répondu [Confirmer] ou [Pas encore].
   */
  proposedStatus?: TerminalActionStatus | null;
  proposedReason?: string | null;
  proposedAt?: string | null;
  createdAt: string;
  updatedAt?: string;
  /** « Demandé » depuis assez de jours ouvrés pour appeler une relance (F-175 / SF-175-06). */
  followUpDue?: boolean;
  /** La nature (F-180 / SF-180-02) ; nulle tant que personne ne l'a dite. */
  categorie?: TerminalActionCategorie | null;
  /** Qui doit agir (F-180 / SF-180-02). */
  qui?: TerminalActionQui | null;
  /** Le nom du sujet du Radar (tableau). */
  subjectName?: string | null;
  /** L'étape du plan F-176 que l'attente bloque (tableau). */
  blockedStep?: TerminalActionBlockedStep | null;
  /** L'interlocuteur normalisé du poste (F-180 / SF-180-03). */
  interlocuteurId?: string | null;
  interlocuteurName?: string | null;
}

/** La nature d'une attente (F-180 / SF-180-02, D1). */
export type TerminalActionCategorie = 'ACCES' | 'VALIDATION' | 'INFO' | 'TRANSMISSION' | 'INCIDENT';

/** Qui doit agir (F-180 / SF-180-02, D1). */
export type TerminalActionQui = 'MOI' | 'CLIENT' | 'TIERS';

export const CATEGORIE_LABELS: Record<TerminalActionCategorie, string> = {
  ACCES: 'Accès',
  VALIDATION: 'Validation',
  INFO: 'Info',
  TRANSMISSION: 'Transmission',
  INCIDENT: 'Incident',
};

export const QUI_LABELS: Record<TerminalActionQui, string> = {
  MOI: 'à toi',
  CLIENT: 'client',
  TIERS: 'tiers',
};

export const CATEGORIES: TerminalActionCategorie[] = ['ACCES', 'VALIDATION', 'INFO', 'TRANSMISSION', 'INCIDENT'];
export const QUIS: TerminalActionQui[] = ['MOI', 'CLIENT', 'TIERS'];

/** L'étape bloquée : « bloque : étape 3 « Déployer » de data-platform ». */
export interface TerminalActionBlockedStep {
  number: number;
  title: string | null;
  terminal: string | null;
}

/** La ligne « bloque : étape … » d'une attente, ou vide. */
export function blockedStepLabel(action: Pick<TerminalAction, 'blockedStep' | 'subjectName'>): string {
  const step = action.blockedStep;
  const parts: string[] = [];
  if (step) {
    parts.push(`étape ${step.number}${step.title ? ` « ${step.title} »` : ''}${step.terminal ? ` de ${step.terminal}` : ''}`);
  }
  if (action.subjectName) {
    parts.push(`sujet ${action.subjectName}`);
  }
  return parts.join(' · ');
}

/** Une attente sans nature et la proposition (`GET /api/terminal-actions/qualify`). */
export interface TerminalActionQualifyItem {
  action: TerminalAction;
  suggestedCategorie: TerminalActionCategorie;
  suggestedQui: TerminalActionQui;
}

/** La nature retenue par l'utilisateur. */
export interface TerminalActionQualifyDecision {
  id: string;
  categorie: TerminalActionCategorie | null;
  qui: TerminalActionQui | null;
}

/** Une attente héritée « à vérifier » et l'état proposé (F-175 / SF-175-07). */
export interface TerminalActionReviewItem {
  action: TerminalAction;
  suggestedStatus: TerminalActionStatus;
}

/** La décision de l'utilisateur sur une attente à vérifier ; `status: null` = garder l'actuel. */
export interface TerminalActionReviewDecision {
  id: string;
  status: TerminalActionStatus | null;
}

/** Ce que la reprise a appliqué. */
export interface TerminalActionReviewResult {
  applied: number;
  ignored: number;
}

/** Un compte d'attentes ouvertes, par poste ou par terminal (F-175 / SF-175-06). */
export interface TerminalActionCount {
  id: string;
  aFaire: number;
  demande: number;
  aRelancer: number;
  oldestOpenAt: string | null;
}

/** Les compteurs du compte (`GET /api/terminal-actions/summary`) : rail de la Forge, mosaïque. */
export interface TerminalActionSummary {
  hosts: TerminalActionCount[];
  terminals: TerminalActionCount[];
}

/**
 * Le brouillon de relance déposé dans la zone de saisie (F-175 / SF-175-06, décision D8) — **jamais
 * envoyé** : l'agent rédige la relance quand l'utilisateur envoie, et rien ne part sans son geste.
 */
export function followUpDraft(action: TerminalAction): string {
  const to = action.requestedTo || action.person;
  const when = action.requestedAt ? ` le ${action.requestedAt.slice(8, 10)}/${action.requestedAt.slice(5, 7)}` : '';
  const channel = action.channel ? ` par ${action.channel}` : '';
  return `Relance${to ? ` ${to}` : ''} au sujet de « ${action.description} » (demandé${when}${channel}, `
    + 'toujours sans réponse). Rédige-moi le message de relance.';
}

/** Une action ouverte d'un **autre** projet (`GET /api/terminal-actions`), en lecture seule. */
export interface TerminalActionElsewhere {
  id: string;
  workspaceId: string;
  workspaceName: string;
  description: string;
  blocks: string | null;
  person: string | null;
  kind: TerminalActionKind;
  status?: TerminalActionStatus;
  createdAt: string;
}

/** Le tableau vu d'un terminal (`GET /api/workspaces/{id}/actions/board`, F-175 / SF-175-01). */
export interface TerminalActionBoard {
  /** Le poste du terminal ; `null` pour un terminal hébergé, qui garde sa liste propre. */
  hostId: string | null;
  /** Les attentes de ce terminal : ouvertes, et fermées depuis 7 jours. */
  here: TerminalAction[];
  /** Celles des autres terminaux du même poste, mêmes règles. */
  host: TerminalAction[];
  aFaire: number;
  demande: number;
  /** Celles « Demandé » dont la relance est due (F-175 / SF-175-06). */
  aRelancer?: number;
  oldestOpenAt: string | null;
  /** Les gestes « à toi » ouverts (F-180 / SF-180-02). */
  moi?: number;
}

/** Un changement d'état (`POST …/actions/{id}/status`). */
export interface TerminalActionStatusChange {
  status: TerminalActionStatus;
  /** La raison d'une fermeture (Fait / Annulé). */
  note?: string | null;
  requestedTo?: string | null;
  channel?: string | null;
}

/** Une édition (`PATCH …/actions/{id}`) : un champ absent ne change pas. */
export interface TerminalActionEdit {
  description?: string;
  blocks?: string;
  person?: string;
  kind?: TerminalActionKind;
  categorie?: TerminalActionCategorie;
  qui?: TerminalActionQui;
}

/** Un ajout à la main (`POST …/actions`). */
export interface TerminalActionCreate {
  description: string;
  blocks?: string | null;
  person?: string | null;
  kind?: TerminalActionKind;
  categorie?: TerminalActionCategorie | null;
  qui?: TerminalActionQui | null;
}

/** Un accès obtenu (`GET /api/workspaces/{id}/acces`, F-180 / SF-180-04) — jamais un secret. */
export interface AccesObtenu {
  id: string;
  system: string;
  account: string | null;
  /** Date ISO `aaaa-mm-jj`. */
  obtainedAt: string;
  grantedBy: string | null;
  interlocuteurId: string | null;
  expiresAt: string | null;
  expired: boolean;
  subjectId: string | null;
  actionId: string | null;
  note: string | null;
  workspaceId: string;
}

/** Un ajout ou une modification du registre ; un champ absent ne change pas. */
export interface AccesRequest {
  actionId?: string | null;
  system?: string;
  account?: string | null;
  obtainedAt?: string | null;
  grantedBy?: string | null;
  expiresAt?: string | null;
  note?: string | null;
}

/** Une attente ACCES faite, proposée au registre. */
export interface AccesProposal {
  action: TerminalAction;
  system: string;
  grantedBy: string | null;
  obtainedAt: string;
}

/** « expire dans 12 j », « expiré », ou vide — à partir de dates ISO. */
export function expiryLabel(expiresAt: string | null, now = new Date()): string {
  if (!expiresAt) {
    return '';
  }
  const end = Date.parse(`${expiresAt}T23:59:59`);
  if (Number.isNaN(end)) {
    return '';
  }
  const days = Math.floor((end - now.getTime()) / 86_400_000);
  if (days < 0) {
    return 'expiré';
  }
  return days === 0 ? "expire aujourd'hui" : `expire dans ${days} j`;
}

/** Un interlocuteur du poste (`GET /api/workspaces/{id}/interlocuteurs`, F-180 / SF-180-03). */
export interface Interlocuteur {
  id: string;
  displayName: string;
  role: string | null;
  team: string | null;
  radarPersonId: string | null;
  radarName: string | null;
  variants: string[];
  openCount: number;
}

/** Une personne et ce qu'on attend d'elle (`GET …/interlocuteurs/board`, F-180 / SF-180-05). */
export interface InterlocuteurBoardRow {
  interlocuteurId: string | null;
  name: string;
  role: string | null;
  openCount: number;
  demandeCount: number;
  oldestOpenAt: string;
  subjects: string[];
  /** Délai moyen demande → réponse, en jours ; `null` sans réponse mesurée. */
  avgResponseDays: number | null;
  responseCount: number;
  avgByCategorie: Partial<Record<TerminalActionCategorie, number>>;
  open: TerminalAction[];
}

/** La mesure par nature, sur le poste (D7). */
export interface InterlocuteurBoardCategorie {
  categorie: TerminalActionCategorie;
  avgResponseDays: number | null;
  responseCount: number;
  openCount: number;
}

export interface InterlocuteurBoard {
  people: InterlocuteurBoardRow[];
  byCategorie: InterlocuteurBoardCategorie[];
}

/** « 4 j », « 1,5 j », « moins d'un jour ». */
export function daysLabel(days: number | null): string {
  if (days === null || days === undefined) {
    return '';
  }
  if (days < 1) {
    return "moins d'un jour";
  }
  const rounded = Math.round(days * 10) / 10;
  return `${String(rounded).replace('.', ',')} j`;
}

/** Au-delà, la relance groupée dit « et N autres » plutôt que de tout lister. */
export const GROUPED_FOLLOW_UP_MAX = 30;

/**
 * **Le brouillon de relance groupée** (F-180 / SF-180-05, décision D5) : **un** texte pour toutes les
 * attentes ouvertes d'une personne, déposé dans la saisie — **jamais envoyé** sans le geste de l'utilisateur.
 * `viaEmail` : l'agent est prié de me l'envoyer par courriel (F-110) pour que je le transfère.
 */
export function groupedFollowUpDraft(row: Pick<InterlocuteurBoardRow, 'name' | 'open'>, viaEmail = false,
                                     now = Date.now()): string {
  const shown = row.open.slice(0, GROUPED_FOLLOW_UP_MAX);
  const lines = shown.map(a => {
    const since = a.requestedAt || a.createdAt;
    const days = Math.max(0, Math.floor((now - Date.parse(since)) / 86_400_000));
    const asked = a.status === 'DEMANDE' ? `demandé il y a ${days} j` : `en attente depuis ${days} j`;
    return `- ${a.description} (${asked}${a.blocks ? ` ; débloque : ${a.blocks}` : ''})`;
  });
  const more = row.open.length > shown.length ? `\n- … et ${row.open.length - shown.length} autre(s)` : '';
  const ask = viaEmail
    ? 'Rédige UN seul message de relance groupé, courtois et précis, et envoie-le-moi par courriel '
      + '(email_me) pour que je le transfère moi-même.'
    : 'Rédige-moi UN seul message de relance groupé, courtois et précis, que je relirai avant de l\'envoyer.';
  return `Relance groupée pour ${row.name} — ${row.open.length} attente${row.open.length > 1 ? 's' : ''} :\n`
    + `${lines.join('\n')}${more}\n${ask}`;
}

/** Une modification d'interlocuteur : un champ absent ne change pas. */
export interface InterlocuteurUpdate {
  displayName?: string;
  role?: string;
  team?: string;
  radarPersonId?: string;
}

/** Pourquoi deux interlocuteurs semblent être la même personne. */
export type InterlocuteurMergeReason = 'PREFIX' | 'INITIALS' | 'TYPO';

export const INTERLOCUTEUR_MERGE_LABELS: Record<InterlocuteurMergeReason, string> = {
  PREFIX: 'un nom prolonge l’autre',
  INITIALS: 'mêmes initiales',
  TYPO: 'à une lettre près',
};

/** Une fusion proposée : rien sans le geste de l'utilisateur. */
export interface InterlocuteurMergeSuggestion {
  keep: Interlocuteur;
  merge: Interlocuteur;
  reason: InterlocuteurMergeReason;
}

/** Pourquoi deux attentes semblent en double (F-180 / SF-180-01). */
export type TerminalActionDuplicateReason = 'KEY' | 'WORDS' | 'MEANING';

/** Les libellés des raisons d'un doublon. */
export const DUPLICATE_REASON_LABELS: Record<TerminalActionDuplicateReason, string> = {
  KEY: 'même clé',
  WORDS: 'mêmes mots',
  MEANING: 'même sens',
};

/**
 * Une paire de doublons probables (`GET …/actions/duplicates`, F-180 / SF-180-01) : la plus ancienne
 * est gardée, la plus récente serait fusionnée — **rien sans le geste de l'utilisateur**.
 */
export interface TerminalActionDuplicate {
  keep: TerminalAction;
  duplicate: TerminalAction;
  reason: TerminalActionDuplicateReason;
}

/** Depuis quand l'action attend — l'ancienneté est le signal qui fait agir. */
export function ageLabel(createdAt: string, now = Date.now()): string {
  const started = Date.parse(createdAt);
  if (Number.isNaN(started)) {
    return '';
  }
  const minutes = Math.max(0, Math.round((now - started) / 60000));
  if (minutes < 60) {
    return minutes <= 1 ? "à l'instant" : `il y a ${minutes} min`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `il y a ${hours} h`;
  }
  const days = Math.round(hours / 24);
  return days === 1 ? 'hier' : `il y a ${days} jours`;
}
