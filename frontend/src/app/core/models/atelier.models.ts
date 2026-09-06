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

/**
 * Cible d'exécution des outils d'un projet (F-38 / SF-38-05, décision D1) : le **sandbox hébergé**
 * chez le fournisseur, ou la **machine de l'utilisateur** via le runner local. Dimension
 * indépendante de la {@link WorkspaceSource} : un dépôt Git cloné sur sa propre machine est un
 * couple `GIT` + `RUNNER` parfaitement légitime.
 */
export type WorkspaceExecutionTarget = 'SANDBOX' | 'RUNNER';

/** Vue résumée d'un workspace (liste). Réponse de `GET /api/workspaces`. */
export interface WorkspaceSummary {
  id: string;
  name: string;
  createdAt: string;
  source: WorkspaceSource;
  /** `owner/repo` pour un projet Git, `null` sinon. */
  gitRepo: string | null;
  /**
   * Cible d'exécution (F-38 / SF-38-05). Champ **additif** : absent d'un backend antérieur ⇒
   * `SANDBOX`, le comportement historique.
   */
  executionTarget?: WorkspaceExecutionTarget;
}

/** Corps de `POST /api/workspaces/{id}/git/push` (F-31 / SF-31-04). Les deux champs sont facultatifs. */
export interface GitPushRequest {
  branch?: string;
  message?: string;
}

/**
 * Résultat d'une publication sur branche (F-31 / SF-31-04).
 *
 * `pushed` est **constaté auprès de GitHub** par le backend, pas déduit de ce que l'agent répond :
 * un agent peut annoncer « poussé » sans l'avoir fait. Quand il vaut `false`, `compareUrl` est nul et
 * `reply` porte la cause.
 */
export interface GitPushResult {
  branch: string;
  pushed: boolean;
  compareUrl: string | null;
  reply: string;
}

/**
 * Corps de `POST /api/workspaces/{id}/git/pull-request` (F-31 / SF-31-05).
 *
 * La branche est **obligatoire** : c'est celle que l'utilisateur vient de publier. La deviner
 * ouvrirait la mauvaise pull request le jour où il en a publié deux.
 */
export interface GitPullRequestRequest {
  branch: string;
  title?: string;
  body?: string;
}

/**
 * Résultat d'une ouverture de pull request (F-31 / SF-31-05).
 *
 * `created` est **constaté auprès de GitHub** par le backend, jamais déduit de ce que l'agent
 * répond : il peut annoncer une création qui n'a pas eu lieu. Quand il vaut `false`, `url` et
 * `number` sont nuls et `reply` porte la cause.
 */
export interface GitPullRequestResult {
  branch: string;
  created: boolean;
  url: string | null;
  number: number | null;
  reply: string;
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
  /**
   * Vrai si l'arborescence est **partielle** (dépôt volumineux). Le dire évite de faire conclure
   * qu'un fichier absent de la liste n'existe pas.
   */
  truncated: boolean;
  /**
   * Chemin du fichier d'instructions du projet (F-34 / SF-34-01) — `CLAUDE.md`, ou son repli
   * `.atelier/instructions.md` — ajouté au prompt de l'agent à la **prochaine ouverture de
   * session**. `null` (ou absent) si le projet n'en porte pas : l'écran n'affiche alors rien.
   */
  instructionsPath?: string | null;
  /**
   * Vrai si le projet demande l'autorisation avant d'exécuter une commande (F-33 / SF-33-01).
   * Champ **additif** : absent d'un backend antérieur ⇒ `false`, le comportement historique.
   */
  askBeforeBash?: boolean;
  /**
   * Cible d'exécution des outils (F-38 / SF-38-05) : `SANDBOX` (sandbox hébergé) ou `RUNNER` (la
   * machine de l'utilisateur). Champ **additif** : absent d'un backend antérieur ⇒ `SANDBOX`.
   */
  executionTarget?: WorkspaceExecutionTarget;
}

/** Corps de `PUT /api/workspaces/{id}/execution-target` (F-38 / SF-38-05). */
export interface ExecutionTargetRequest {
  executionTarget: WorkspaceExecutionTarget;
}

/**
 * État runner d'un projet (F-38 / SF-38-02), réponse de `GET /api/workspaces/{id}/runner/status`.
 *
 * <p>`connected` n'est **pas** du temps réel : le backend le calcule à partir du registre de
 * présence et de la fraîcheur du dernier heartbeat (`app.runner.heartbeat.stale-after`, 90 s par
 * défaut). Un runner coupé par `Ctrl-C` reste donc annoncé connecté jusqu'à ce délai — l'écran doit
 * le dire plutôt que de laisser croire à une pastille instantanée.</p>
 */
export interface RunnerStatus {
  connected: boolean;
  /** Dernier signe de vie observé, ou `null` si aucun runner ne s'est jamais signalé. */
  lastSeenAt: string | null;
}

/**
 * Code d'appairage runner (F-38 / SF-38-01), réponse de
 * `POST /api/workspaces/{id}/runner/pairing-code`. **À usage unique**, TTL court
 * (`app.runner.pairing-code-ttl`, 5 min par défaut) : il n'apparaît qu'ici, n'est jamais réexposé
 * par l'API, et ne doit donc être ni stocké ni ré-affiché après consommation.
 */
export interface RunnerPairingCode {
  code: string;
  expiresAt: string;
}

/**
 * Ligne du journal d'activité du runner (F-38 / SF-38-08, décision D11), réponse de
 * `GET /api/workspaces/{id}/runner/audit`. Elle dit **ce qui a été fait** sur la machine — jamais
 * le contenu lu ni la sortie produite.
 */
export interface RunnerAuditEntry {
  id: string;
  callId: string;
  /**
   * `list_files` | `read_file` | `write_file` | `edit_file` | `search_files` | `bash` |
   * `bootstrap` | `kill_switch`.
   */
  tool: string;
  /** Chemin, terme recherché ou commande. `null` pour un listage. */
  target: string | null;
  /** `OK` | `ERROR` | `DENIED` | `TIMEOUT` | `CANCELLED`. */
  outcome: string;
  errorCode: string | null;
  exitCode: number | null;
  durationMs: number | null;
  bytes: number | null;
  createdAt: string;
}

/**
 * Résultat du coupe-circuit (F-38 / SF-38-08), réponse de
 * `POST /api/workspaces/{id}/runner/kill`. Le projet repasse en cible `SANDBOX` : la boucle ne
 * route plus rien vers la machine, et le runner ne peut plus se reconnecter (jetons révoqués).
 */
export interface RunnerKillResult {
  revokedTokens: number;
  disconnected: boolean;
  executionTarget: WorkspaceExecutionTarget;
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
 * Modification d'un fichier constatée par le backend à la resynchronisation d'un tour (F-37 /
 * SF-37-01) : **ce qui a changé**, et pas seulement le chemin touché.
 *
 * Le diff est déjà calculé et **borné** côté serveur — l'écran ne le recalcule jamais, il le lit.
 */
export interface AtelierFileDiff {
  /** Chemin relatif au workspace. */
  path: string;
  /** Le fichier n'existait pas avant ce tour : le diff est un ajout intégral. */
  added: boolean;
  /** Diff unifié (lignes `@@`, ` `, `-`, `+`), séparateur `\n`. Vide si `unreadable`. */
  diff: string;
  addedLines: number;
  removedLines: number;
  /** Lignes de diff écartées par la borne par fichier ; `0` si le diff est complet. */
  omittedLines: number;
  /** Contenu non textuel : aucune comparaison n'était possible. */
  unreadable: boolean;
}

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
    /**
     * Fil d'exécution dont vient la commande (F-35 / SF-35-02). Absent ou `null` pour un run
     * séquentiel — et pour tous les tours écrits avant F-35, qui restent lisibles sans marquage.
     */
    threadId?: string | null;
  }[];
  omittedBlocks: number;
  inputTokens: number;
  outputTokens: number;
  activeSeconds: number;
  /**
   * Le tour s'est arrêté sur une demande d'interruption (F-32 SF-32-01). Absent des tours écrits
   * avant cette version : traité comme `false`.
   */
  interrupted?: boolean;
  /**
   * Le tour s'est arrêté sur le **plafond de dépense du run** (F-36 SF-36-01). Absent des tours
   * écrits avant cette version : traité comme `false`.
   */
  budgetReached?: boolean;
  /**
   * Modifications de fichiers du tour (F-37 / SF-37-01). **Absent** des tours écrits avant cette
   * version, et des tours qui n'ont rien modifié : traité comme une liste vide.
   */
  diffs?: AtelierFileDiff[];
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
  /**
   * Type d'étape. **Chaîne libre volontairement** (contrat de messages runner §3) : le backend peut
   * en ajouter (`bash` arrive en SF-38-07) et l'écran doit tolérer un type qu'il ne connaît pas
   * plutôt que de l'afficher sous une étiquette fausse.
   */
  type: string;
  path?: string;
  /**
   * Sortie de la commande accumulée au fil de l'eau (F-38 / SF-38-07, événement SSE `output`).
   * Absente pour toute étape autre que `bash`.
   */
  output?: string;
}

/** Métadonnées de fin de flux d'atelier (événement SSE `done`, SF-28-05). */
export interface AtelierStreamDone {
  reply: string;
  actions: AtelierAction[];
  messageId: string;
  /**
   * Consommation du tour, cache compris (F-39 / SF-39-15). **Optionnelle** : un backend antérieur
   * ne l'émet pas, et l'écran n'affiche alors aucun coût — mieux vaut ne rien dire qu'annoncer
   * « 0 token » (même règle qu'un relevé manqué côté agent, F-30 / SF-30-05).
   */
  inputTokens?: number;
  outputTokens?: number;
  /** Durée d'horloge du tour, en secondes (F-39 / SF-39-15). */
  activeSeconds?: number;
  /**
   * Le tour s'est arrêté sur le **plafond de consommation** de ce message (F-39 / SF-39-15) —
   * jamais sur le budget de temps, qui dit déjà sa cause dans `reply`.
   */
  budgetReached?: boolean;
}

/** Callbacks du streaming de l'atelier (SF-28-05). */
export interface AtelierStreamHandlers {
  onAction: (action: AtelierStreamAction) => void;
  onText: (text: string) => void;
  onDone: (done: AtelierStreamDone) => void;
  onError: (code: string) => void;
  /**
   * Fragment de sortie de commande (F-38 / SF-38-07). **Optionnel** : un appelant qui ne s'y abonne
   * pas ne voit aucune différence, et un backend antérieur n'émet jamais cet événement.
   */
  onOutput?: (chunk: string) => void;
  /**
   * L'agent demande l'autorisation d'exécuter une commande sur la machine connectée
   * (F-38 / SF-38-08). Le tour est **suspendu** tant que rien n'est décidé, et le silence vaut
   * refus. **Optionnel** : un appelant qui ne s'y abonne pas verra la commande refusée à
   * l'échéance — jamais exécutée par défaut.
   */
  onConfirmRequest?: (request: AtelierConfirmRequest) => void;
  /** Demande tranchée (ici, ailleurs, ou par expiration) : l'invite n'a plus lieu d'être. */
  onConfirmResolved?: (resolved: AtelierConfirmResolved) => void;
  /**
   * Consommation **cumulée** du tour, relayée après chaque itération (F-39 / SF-39-15). C'est ce
   * qui remplit les tokens de la ligne vivante (acquis §4 n°5), muette sur la boucle maison
   * jusqu'ici. **Optionnel** : un appelant qui ne s'y abonne pas ne voit aucune différence.
   */
  onProgress?: (tokens: number) => void;
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
  /**
   * Fil d'exécution dont vient la commande (F-35 / SF-35-02) : chaîne **opaque**, jamais affichée
   * telle quelle. `null` pour un run séquentiel.
   */
  threadId?: string | null;
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
  /**
   * Le tour s'est arrêté sur une demande d'interruption (F-32 SF-32-01). Champ **additif** : absent,
   * il vaut `false` et le tour s'affiche comme un tour mené à son terme.
   */
  interrupted: boolean;
  /**
   * Le tour s'est arrêté sur le **plafond de dépense de ce run** (F-36 SF-36-01) — distinct du quota
   * mensuel épuisé : le travail est conservé, et relancer repart d'un plafond neuf dans la même
   * sandbox. Champ **additif** : absent, il vaut `false`.
   */
  budgetReached?: boolean;
  /**
   * Modifications de fichiers du tour (F-37 / SF-37-01) : le contenu de ce qui a changé, calculé et
   * borné par le backend. Champ **additif** — absent d'un backend antérieur, il vaut liste vide, et
   * l'écran se comporte alors exactement comme avant F-37.
   */
  diffs?: AtelierFileDiff[];
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
  /** Fil d'exécution dont vient la sortie (F-35 / SF-35-02) ; `null` pour un run séquentiel. */
  threadId?: string | null;
}

/**
 * Demande d'autorisation posée par l'agent (F-33 / SF-33-02) : la session est **en pause** tant
 * qu'aucune décision n'est envoyée. `toolUseId` est l'identifiant à renvoyer pour trancher.
 */
export interface AtelierConfirmRequest {
  toolUseId: string;
  tool: string;
  detail: string;
}

/**
 * Décision prise sur une demande d'autorisation (F-33 / SF-33-02). `timeout` signale le refus
 * automatique de fin de délai : personne n'a répondu, la commande n'a pas été exécutée.
 */
export interface AtelierConfirmResolved {
  toolUseId: string;
  decision: 'allow' | 'deny' | 'timeout';
}

/** Corps de `POST /api/workspaces/{id}/agent/confirm` (F-33 / SF-33-02). */
export interface AtelierConfirmDecision {
  toolUseId: string;
  decision: 'allow' | 'deny';
  reason?: string;
}

/** Réponse de `PUT /api/workspaces/{id}/agent/confirmation` (F-33 / SF-33-01). */
export interface AtelierConfirmationState {
  enabled: boolean;
  /** Faux si une sandbox est déjà ouverte : elle garde la politique posée à son ouverture. */
  appliesToCurrentSession: boolean;
}

/** Callbacks du streaming du mode « Exécution » (Phase 2, SF-28-11 ; `onActionResult` F-30 SF-30-02). */
export interface AtelierAgentStreamHandlers {
  onAgent: (text: string) => void;
  onAction: (action: AtelierAgentStreamAction) => void;
  onActionResult: (result: AtelierAgentStreamActionResult) => void;
  onStatus: (state: string) => void;
  onDone: (done: AtelierAgentStreamDone) => void;
  onError: (code: string) => void;
  /**
   * Consommation du tour en cours, en tokens (F-30 / SF-30-13). **Facultatif** : l'événement est
   * additif, et un backend antérieur ne l'émet jamais — l'appelant qui ne le fournit pas se comporte
   * exactement comme avant.
   */
  onProgress?: (tokens: number) => void;
  /**
   * Demande d'autorisation à afficher (F-33 / SF-33-02). **Facultatif** : ces événements sont
   * additifs, un appelant qui ne les fournit pas se comporte comme avant F-33.
   */
  onConfirmRequest?: (request: AtelierConfirmRequest) => void;
  /** Demande tranchée (ici, ailleurs, ou par expiration) : l'invite n'a plus lieu d'être. */
  onConfirmResolved?: (resolved: AtelierConfirmResolved) => void;
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
  /**
   * Fil d'exécution dont vient le bloc (F-35 / SF-35-03) : sert à distinguer une sous-tâche du
   * travail principal. Chaîne **opaque** — jamais affichée telle quelle, elle n'a aucun sens pour
   * l'utilisateur. `null` pour un run séquentiel.
   */
  threadId: string | null;
  output: string;
  /** Vrai dès qu'une sortie a été reçue : distingue « pas encore de sortie » de « sortie vide ». */
  hasOutput: boolean;
  error: boolean;
  /** Repli de l'affichage des sorties longues (piloté par l'utilisateur). */
  expanded: boolean;
}

/**
 * Résultat d'une publication de modifications faites depuis l'écran (F-31 / SF-31-08).
 *
 * `pullRequestUrl` n'est renseigné que si une pull request est **déjà** ouverte sur cette branche :
 * publier un commit n'en ouvre pas.
 */
export interface GitCommitResult {
  branch: string;
  commitSha: string;
  branchCreated: boolean;
  compareUrl: string;
  pullRequestUrl?: string | null;
}

/**
 * Branches d'un projet Git (F-31 / SF-31-10) : celles du dépôt, celle que suit le projet, et celle
 * par défaut — la seule sur laquelle publier reste interdit.
 */
export interface GitBranches {
  branches: string[];
  current: string;
  defaultBranch: string;
}

/**
 * État de reprise du fil d'Atelier (F-39 / SF-39-04, décision D5), réponse de
 * `GET /api/workspaces/{id}/chat/resume`. Par défaut le fil reprend en silence : `prompt` ne vaut
 * `IDLE` que lorsque la reprise ne va pas de soi et qu'il faut poser la question.
 */
/**
 * Moteur qui anime le terminal d'un projet (F-39 / SF-39-07, décisions D1 et D-L4-2). L'utilisateur
 * ne le choisit **jamais** : la gateway le résout, l'écran le lit. Les noms disent **où le code
 * s'exécute** — la seule chose que l'utilisateur ait à comprendre.
 */
export type AtelierEngine = 'LOCAL_MACHINE' | 'HOSTED_SANDBOX';

/**
 * Limite du bac à sable qui justifie de proposer le runner (F-39 / SF-39-07, décision D6). Il n'y a
 * pas de motif « générique » : la proposition ne tombe que sur une limite réellement rencontrée.
 */
export type AtelierRunnerRecommendation = 'GIT' | 'FILE_LIMIT';

/**
 * Moteur d'un projet, réponse de `GET /api/workspaces/{id}/engine` (F-39 / SF-39-07).
 *
 * `runnerConnected` est **indépendant** de `engine` : une cible « ma machine » dont le runner est
 * éteint reste en `LOCAL_MACHINE`, et l'écran dit « runner hors ligne » plutôt que de basculer en
 * silence vers un bac à sable vide (décision D-L4-1).
 */
export interface AtelierEngineStatus {
  engine: AtelierEngine;
  runnerConnected: boolean;
  runnerLastSeenAt: string | null;
  recommendRunner: boolean;
  recommendReason: AtelierRunnerRecommendation | null;
}

export interface AtelierResume {
  /** Messages que le prochain tour rejouera au fournisseur. */
  turns: number;
  lastMessageAt: string | null;
  /** Frontière posée par un « nouveau départ », ou `null` si aucun. */
  threadStartedAt: string | null;
  /** `NONE` — ne rien demander ; `IDLE` — projet inactif, proposer le choix. */
  prompt: 'NONE' | 'IDLE';
}
