/**
 * Contrats DTO de l'Atelier (F-28 « Claude Code Lite »). Figés par le backend
 * (SF-28-01 workspaces, SF-28-02 boucle tool-use). Le frontend ne communique qu'avec la Gateway
 * (`/api/...`), jamais directement avec un fournisseur IA ; l'isolation `user_id` est garantie
 * côté backend via le JWT porté par l'`authInterceptor`.
 */

/**
 * Provenance des fichiers d'un projet (F-31 / SF-31-02) : archive `.zip` téléversée, ou dépôt Git
 * cloné dans l'espace d'exécution. Les écrans sont communs ; seuls les gestes disponibles diffèrent.
 */
export type WorkspaceSource = 'ARCHIVE' | 'GIT';

/** Vue résumée d'un workspace (liste). Réponse de `GET /api/workspaces`. */
export interface WorkspaceSummary {
  id: string;
  name: string;
  createdAt: string;
  source: WorkspaceSource;
  /** `owner/repo` pour un projet Git, `null` sinon. */
  gitRepo: string | null;
}

/** Corps de `POST /api/workspaces/git` (F-31 / SF-31-02). Aucun secret : le jeton est déjà enregistré. */
export interface CreateGitWorkspaceRequest {
  repoUrl: string;
  branch?: string;
  name?: string;
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
  source: WorkspaceSource;
  /** URL publique du dépôt (jamais le jeton), `null` pour un projet d'archive. */
  gitRepoUrl: string | null;
  /** `owner/repo`, `null` pour un projet d'archive. */
  gitRepo: string | null;
  /** Branche montée dans l'espace d'exécution, `null` pour un projet d'archive. */
  gitBranch: string | null;
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

/**
 * Transcription d'un tour Terminal telle que stockée (F-30 SF-30-09) : commandes appariées à leurs
 * sorties côté backend, coût du tour, et nombre de blocs omis par la borne de persistance.
 */
export interface AtelierPersistedTranscript {
  blocks: {
    tool: string;
    command: string | null;
    toolUseId: string | null;
    output: string;
    hasOutput: boolean;
    error: boolean;
  }[];
  omittedBlocks: number;
  inputTokens: number;
  outputTokens: number;
  activeSeconds: number;
}

/** Message de l'historique. Réponse de `GET /api/workspaces/{id}/chat`. */
export interface AtelierMessage {
  id: string;
  role: AtelierRole;
  content: string;
  createdAt: string;
  /** Transcription du tour Terminal (F-30 SF-30-09) ; absente pour les tours du mode Assistant. */
  terminal?: AtelierPersistedTranscript | null;
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
  /** Identifiant de l'appel d'outil, qui apparie la commande à sa sortie (F-30). `null` si absent. */
  toolUseId?: string | null;
}

/**
 * Métadonnées de fin du flux d'exécution (événement SSE `done`, Phase 2). `changedFiles` = chemins
 * relatifs des fichiers réellement modifiés par l'agent pendant la session.
 */
export interface AtelierAgentStreamDone {
  reply: string;
  changedFiles: string[];
  /**
   * Consommation du **tour** (F-30 SF-30-05) : exactement ce qui est décompté du quota, jamais le
   * cumul de la session. `0` signifie **inconnu** (relevé best-effort manqué côté backend) — dans ce
   * cas rien n'est affiché, un « 0 token » après une exécution réelle serait faux.
   */
  inputTokens: number;
  outputTokens: number;
  activeSeconds: number;
}

/**
 * Sortie d'une commande relayée par le flux d'exécution (événement SSE `action_result`, F-30 SF-30-01).
 * `toolUseId` apparie la sortie à la commande correspondante ; il peut être absent, auquel cas le
 * rattachement se fait à la dernière commande sans sortie. `output` est déjà tronqué côté backend.
 */
export interface AtelierAgentStreamActionResult {
  tool: string;
  toolUseId: string | null;
  output: string;
  error: boolean;
}

/** Callbacks du streaming du mode « Exécution » (Phase 2, SF-28-11 ; `onActionResult` F-30 SF-30-02). */
export interface AtelierAgentStreamHandlers {
  onAgent: (text: string) => void;
  onAction: (action: AtelierAgentStreamAction) => void;
  onActionResult: (result: AtelierAgentStreamActionResult) => void;
  onStatus: (state: string) => void;
  onDone: (done: AtelierAgentStreamDone) => void;
  onError: (code: string) => void;
}

/**
 * Bloc de transcription du rendu terminal (F-30 SF-30-02) : une commande et la sortie qu'elle a
 * produite. `command` est absent pour un bloc « orphelin » — une sortie qu'aucune commande connue
 * ne réclame : mieux vaut l'afficher sans en-tête que la perdre.
 */
export interface AtelierTerminalBlock {
  tool: string;
  command?: string;
  toolUseId: string | null;
  output: string;
  /** Vrai dès qu'une sortie a été reçue : distingue « pas encore de sortie » de « sortie vide ». */
  hasOutput: boolean;
  error: boolean;
  /** Repli de l'affichage des sorties longues (piloté par l'utilisateur). */
  expanded: boolean;
}
