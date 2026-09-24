/**
 * **Les actions à faire d'un terminal** (F-151) : ce que l'utilisateur doit faire, lui, pour qu'un
 * tour bloqué reprenne.
 */

/** La nature du geste attendu : un message à écrire, ou autre chose à faire. */
export type TerminalActionKind = 'ACTION' | 'MESSAGE';

/** Les trois états d'une action, et les trois seulement. */
export type TerminalActionStatus = 'OPEN' | 'DONE' | 'CANCELLED';

/** Une action du terminal courant (`GET /api/workspaces/{id}/actions`). */
export interface TerminalAction {
  id: string;
  workspaceId: string;
  subjectId: string | null;
  description: string;
  /** Ce que l'action débloque — la raison qui la rend urgente. */
  blocks: string | null;
  person: string | null;
  kind: TerminalActionKind;
  status: TerminalActionStatus;
  /** La phrase qui a fermé l'action : sans elle, on ne saurait plus pourquoi elle a disparu. */
  closedReason: string | null;
  closedAt: string | null;
  createdAt: string;
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
  createdAt: string;
}
