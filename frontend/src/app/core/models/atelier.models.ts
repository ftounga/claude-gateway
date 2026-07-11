/**
 * Contrats DTO de l'Atelier (F-28 « Claude Code Lite »). Figés par le backend
 * (SF-28-01 workspaces, SF-28-02 boucle tool-use). Le frontend ne communique qu'avec la Gateway
 * (`/api/...`), jamais directement avec un fournisseur IA ; l'isolation `user_id` est garantie
 * côté backend via le JWT porté par l'`authInterceptor`.
 */

/** Vue résumée d'un workspace (liste). Réponse de `GET /api/workspaces`. */
export interface WorkspaceSummary {
  id: string;
  name: string;
  createdAt: string;
}

/**
 * Vue détaillée d'un workspace : métadonnées + arborescence (chemins relatifs).
 * Réponse de `GET /api/workspaces/{id}` et de `POST /api/workspaces`.
 */
export interface WorkspaceDetail {
  id: string;
  name: string;
  fileCount: number;
  files: string[];
  createdAt: string;
}

/** Contenu texte d'un fichier du workspace. Réponse de `GET /api/workspaces/{id}/file?path=`. */
export interface FileContent {
  path: string;
  content: string;
}

/** Corps de `PUT /api/workspaces/{id}/file?path=`. */
export interface WriteFileRequest {
  content: string;
}

/** Rôle d'un message Atelier tel que persisté par le backend. */
export type AtelierRole = 'USER' | 'ASSISTANT';

/** Message de l'historique. Réponse de `GET /api/workspaces/{id}/chat`. */
export interface AtelierMessage {
  id: string;
  role: AtelierRole;
  content: string;
  createdAt: string;
}

/** Action de fichier réalisée par l'agent pendant un tour : `type` = `read` ou `write`. */
export interface AtelierAction {
  type: string;
  path: string;
}

/** Corps de `POST /api/workspaces/{id}/chat`. */
export interface AtelierChatRequest {
  message: string;
}

/** Réponse de `POST /api/workspaces/{id}/chat`. */
export interface AtelierChatResponse {
  reply: string;
  actions: AtelierAction[];
  messageId: string;
}

/**
 * Étape d'action relayée au fil de l'eau par le flux SSE de `POST /api/workspaces/{id}/chat/stream`
 * (événement `action`, SF-28-05). `path` est absent pour `list`.
 */
export interface AtelierStreamAction {
  type: 'read' | 'write' | 'list' | 'search';
  path?: string;
}

/** Métadonnées de fin de flux d'atelier (événement SSE `done`, SF-28-05). */
export interface AtelierStreamDone {
  reply: string;
  actions: AtelierAction[];
  messageId: string;
}

/** Callbacks du streaming de l'atelier (SF-28-05). */
export interface AtelierStreamHandlers {
  onAction: (action: AtelierStreamAction) => void;
  onText: (text: string) => void;
  onDone: (done: AtelierStreamDone) => void;
  onError: (code: string) => void;
}

/**
 * Étape d'exécution relayée au fil de l'eau par le flux SSE du mode « Exécution » (Phase 2,
 * `POST /api/workspaces/{id}/agent/stream`, événement `action`). `tool` = outil invoqué dans le
 * sandbox Anthropic (ex. `bash`), `detail` = commande/argument (ex. `npm test`).
 */
export interface AtelierAgentStreamAction {
  tool: string;
  detail?: string;
}

/**
 * Métadonnées de fin du flux d'exécution (événement SSE `done`, Phase 2). `changedFiles` = chemins
 * relatifs des fichiers réellement modifiés par l'agent pendant la session.
 */
export interface AtelierAgentStreamDone {
  reply: string;
  changedFiles: string[];
}

/** Callbacks du streaming du mode « Exécution » (Phase 2, SF-28-11). */
export interface AtelierAgentStreamHandlers {
  onAgent: (text: string) => void;
  onAction: (action: AtelierAgentStreamAction) => void;
  onStatus: (state: string) => void;
  onDone: (done: AtelierAgentStreamDone) => void;
  onError: (code: string) => void;
}
