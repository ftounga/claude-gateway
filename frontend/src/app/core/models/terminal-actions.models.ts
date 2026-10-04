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
  oldestOpenAt: string | null;
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
}

/** Un ajout à la main (`POST …/actions`). */
export interface TerminalActionCreate {
  description: string;
  blocks?: string | null;
  person?: string | null;
  kind?: TerminalActionKind;
}
