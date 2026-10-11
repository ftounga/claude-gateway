/**
 * **Le fil des tâches** (F-191) — contrat de `GET /api/workspaces/{id}/fil` (SF-191-01, figé).
 * Dates en millisecondes epoch ; `serverTime` sert à calculer les durées sur l'horloge serveur.
 */
export type FilTaskState = 'waiting' | 'running' | 'done' | 'failed' | 'interrupted';

export interface FilSubAgent {
  kind: string;
  label: string | null;
  state: 'running' | 'done';
  startedAt: number;
}

export interface FilTask {
  turnId: string;
  workspaceId: string;
  workspaceName: string;
  /** Terminal du poste (la racine). */
  root: boolean;
  /** Le terminal qui pose la question. */
  here: boolean;
  state: FilTaskState;
  waiting: 'approval' | 'question' | null;
  demand: string | null;
  startedAt: number;
  endedAt: number | null;
  planDone: number;
  planTotal: number;
  planStep: string | null;
  tool: string | null;
  subagents: FilSubAgent[];
}

export interface PosteTurns {
  hostId: string | null;
  serverTime: number;
  turns: FilTask[];
}
