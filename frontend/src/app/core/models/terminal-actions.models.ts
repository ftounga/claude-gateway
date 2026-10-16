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
