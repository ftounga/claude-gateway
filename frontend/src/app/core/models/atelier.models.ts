/**
 * Contrats DTO de l'Atelier (F-28 « Claude Code Lite »). Figés par le backend
 * (SF-28-01 workspaces, SF-28-02 boucle tool-use). Le frontend ne communique qu'avec la Gateway
 * (`/api/...`), jamais directement avec un fournisseur IA ; l'isolation `user_id` est garantie
 * côté backend via le JWT porté par l'`authInterceptor`.
 */

import { HostMissionStatus } from '../../shared/mission-status';

export type { HostMissionStatus };

/**
 * Provenance des fichiers d'un projet (F-31 / SF-31-02) : archive `.zip` téléversée, ou dépôt Git
 * cloné dans l'espace d'exécution. Les écrans sont communs ; seuls les gestes disponibles diffèrent.
 */
/**
 * Provenance des fichiers d'un projet. `LOCAL` (F-38 / SF-38-15) : le projet vit **déjà sur la
 * machine** de l'utilisateur — ni archive, ni dépôt. C'est le runner qui en déclare la racine à
 * l'appairage ; la gateway n'apprend au plus que le nom du dossier.
 */
export type WorkspaceSource = 'ARCHIVE' | 'GIT' | 'LOCAL';

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
  /**
   * **Nom du poste** sur lequel ce projet vit (F-49 / SF-49-03), ou `null`/absent s'il n'est
   * rattaché à aucune machine. C'est lui qui porte l'appartenance à l'écran — la liste des projets
   * et l'en-tête du terminal en tirent la pastille, les initiales et la couleur du poste.
   *
   * <p>La **couleur n'est jamais transmise** : elle se calcule à l'écran à partir de ce nom
   * (`shared/host-identity.ts`), ce qui la rend identique d'une session à l'autre et d'un poste de
   * consultation à l'autre. Champ **additif** : absent d'un backend antérieur ⇒ rien n'est
   * affiché.</p>
   */
  hostName?: string | null;
  /**
   * **État de mission** du poste sur lequel ce projet vit (F-60 / SF-60-02), ou `null`/absent quand
   * le projet n'est rattaché à aucune machine.
   *
   * <p>Il voyage avec `hostName`, par la même lecture et sous la même isolation : c'est ce qui
   * permet à la liste des projets et à l'en-tête du terminal de dire **où en est la mission** sans
   * un appel de plus. Champ **additif** : absent d'un backend antérieur ⇒ lu comme `ACTIVE`.</p>
   */
  hostMissionStatus?: HostMissionStatus | null;
  /**
   * Vrai si cette ligne est le **terminal du poste** (F-74 / SF-74-01) et non un projet : le
   * terminal rattaché à la machine, posé à sa racine, pour les gestes qui n'appartiennent à aucun
   * projet — cloner un dépôt le premier jour, monter un VPN, lancer `terraform`.
   *
   * <p>La liste latérale le **montre** — c'est par là qu'on y revient — avec son icône propre et
   * **sans** le geste « supprimer le projet » : il n'en est pas un, et il se supprime avec son
   * poste. Champ **additif** : absent d'un backend antérieur ⇒ `false`, un projet.</p>
   */
  hostTerminal?: boolean;
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

  /**
   * **Poste** sur lequel ce projet vit (F-48 / SF-48-01), ou `null`/absent s'il n'est rattaché à
   * aucune machine — l'état d'un projet qu'on vient de créer.
   *
   * <p>C'est le déplacement d'unité de F-48 : la racine, le runner et l'appairage appartiennent à la
   * machine, et le projet n'est qu'un dossier dessous. Ce que le runner déclare de la machine — sa
   * racine, son système, ses droits, son interpréteur — se lit donc sur le poste, plus sur le
   * projet.</p>
   */
  hostId?: string | null;

  /**
   * Chemin du projet **relatif à la racine du poste**, séparateur `/`. La chaîne vide désigne la
   * racine elle-même ; `null`/absent, un projet non rattaché.
   */
  projectPath?: string | null;

  /**
   * Vrai si ce workspace est le **terminal du poste** (F-74 / SF-74-01) et non un projet. Champ
   * **additif** : absent d'un backend antérieur ⇒ `false`.
   */
  hostTerminal?: boolean;
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
  /**
   * Genre d'interpréteur **élu** par le runner et déclaré à la gateway (F-38 / SF-38-27), relevé
   * avec l'état depuis F-45 / SF-45-05 : `posix`, `powershell` ou `cmd`.
   *
   * Champ **additif** et facultatif : une gateway antérieure ne l'envoie pas, un runner antérieur
   * n'en a jamais déclaré. L'écran **omet la ligne** dans ce cas — jamais « inconnu », qui se
   * lirait comme un défaut.
   */
  shell?: string | null;
  /**
   * **Poste** dont cet état est celui (F-48 / SF-48-01), ou `null` quand le projet n'est rattaché à
   * aucune machine. C'est lui que visent le coupe-circuit et la génération d'un code d'appairage.
   */
  hostId?: string | null;
  /** Nom du poste, ou `null` quand le projet n'est rattaché à aucune machine. */
  hostName?: string | null;
  /**
   * Dernier segment de la racine que le runner a déclarée (ex. `dev`), jamais le chemin absolu.
   * `null` tant qu'aucun runner ne s'est appairé sur ce poste.
   */
  rootName?: string | null;
  /**
   * Vrai si le runner de ce poste tourne avec les droits de l'**administrateur** (F-38 / SF-38-18).
   * Lu là où l'on autorise une commande : c'est le seul endroit où l'information change une
   * décision.
   */
  elevated?: boolean;
}

/**
 * Un **poste** (F-48 / SF-48-01) : une machine connectée, avec une racine et un runner, appairée
 * **une seule fois**. Réponse de `GET /api/runner-hosts`.
 *
 * <p>Tout ce que la gateway sait de la machine est **déclaré par le runner**, jamais deviné :
 * `rootName` n'est que le dernier segment de la racine, jamais le chemin absolu.</p>
 */
export interface RunnerHost {
  id: string;
  /** Nom libre, choisi par l'utilisateur — rien n'empêche d'y mettre le nom d'un client. */
  name: string;
  /** Dernier segment de la racine déclarée (ex. `dev`), ou `null` tant qu'aucun runner ne s'est appairé. */
  rootName?: string | null;
  os?: string | null;
  /** `posix`, `powershell` ou `cmd`, ou `null` si aucun runner ne l'a déclaré. */
  shell?: string | null;
  /** Vrai si le runner tourne avec les droits de l'administrateur (F-38 / SF-38-18). */
  elevated?: boolean | null;
  /** Un runner de ce poste est joignable maintenant, tous replicas confondus. */
  connected: boolean;
  /**
   * **État de mission** déclaré par le propriétaire (F-60 / SF-60-01) : `ACTIVE`, `PENDING` ou
   * `CLOSED`. Indépendant de `connected`, qui est l'état **technique** : un poste éteint peut
   * porter une mission active en pause, un poste connecté une mission close qu'on n'a pas rangée.
   */
  missionStatus?: HostMissionStatus | null;
  lastSeenAt?: string | null;
  createdAt: string;
}

/** Corps de création et de renommage d'un poste (F-48 / SF-48-01). */
export interface RunnerHostRequest {
  name: string;
}

/**
 * Corps de `PUT /api/runner-hosts/{id}/mission` (F-60 / SF-60-01) : l'état de mission **déclaré**.
 *
 * <p>Chemin distinct du renommage à dessein : un corps de renommage qui n'enverrait pas l'état
 * remettrait la mission « en cours » sans que personne l'ait demandé.</p>
 */
export interface HostMissionRequest {
  missionStatus: HostMissionStatus;
}

/**
 * Corps de `PUT /api/workspaces/{id}/host` (F-48 / SF-48-01) : **rattacher** un projet à un poste.
 *
 * <p>C'est le geste qui remplace un appairage. Le poste est appairé une fois ; ouvrir un projet de
 * plus sous sa racine ne coûte plus que cette requête.</p>
 */
export interface AttachHostRequest {
  /** Poste visé, ou `null` pour détacher le projet. */
  hostId: string | null;
  /** Chemin relatif sous la racine du poste ; vide = la racine elle-même. */
  projectPath?: string;
}

/**
 * Code d'appairage runner (F-38 / SF-38-01), réponse de
 * `POST /api/workspaces/{id}/runner/pairing-code`. **À usage unique**, TTL court
 * (`app.runner.pairing-code-ttl`, 5 min par défaut) : il n'apparaît qu'ici, n'est jamais réexposé
 * par l'API, et ne doit donc être ni stocké ni ré-affiché après consommation.
 */
/**
 * Formats de runner disponibles sur la gateway (F-44 / SF-44-02, étendu par SF-44-03).
 *
 * Les champs autres que `jar` portent les **paquets autonomes** — le runner et sa propre JVM —
 * pour les postes où aucun Java 21 n'est installable : Windows, puis macOS Apple Silicon et Intel.
 * Une gateway déployée avant F-44 les renvoie tous à `false`, une gateway déployée entre SF-44-02
 * et SF-44-03 n'a que celui de Windows : l'écran masque alors les formats absents au lieu d'offrir
 * un lien mort.
 */
export interface RunnerDownloadFormats {
  jar: boolean;
  windowsPackage: boolean;
  macosAarch64Package: boolean;
  macosX64Package: boolean;
}

/**
 * Relais `px` servis par **cette** gateway (F-59 / SF-59-01).
 *
 * L'assistant proxy le faisait télécharger depuis GitHub — souvent bloqué **par catégorie** sur un
 * poste d'entreprise, ce qui enferme l'utilisateur : il lui faut le relais pour sortir, et une
 * sortie pour l'obtenir. Le domaine de la gateway, lui, est forcément autorisé.
 *
 * `license` porte la notice **MIT** de `px` : sans elle, la gateway ne sert **aucune** archive — la
 * redistribution en dépend. Une gateway déployée avant F-59 renvoie tout à `false`, et l'écran
 * retombe alors sur le lien GitHub au lieu d'offrir un lien mort.
 */
export interface ProxyRelayFormats {
  windows: boolean;
  macosAarch64: boolean;
  linuxX64: boolean;
  license: boolean;
  /** Version amont servie, citée à l'écran (ex. `v0.11.0`). */
  version: string;
}

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
  /**
   * Nombre de projets du poste ramenés à la cible `SANDBOX` (F-48 / SF-48-01). On ne coupe pas un
   * dossier mais une machine : ne ramener qu'un projet laisserait les autres pointer vers un runner
   * mort.
   */
  workspacesReturned: number;
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

/** Une étape du plan de travail de l'agent (F-39 / SF-39-13). */
export interface AtelierPlanStep {
  title: string;
  /** `pending` | `active` | `done` — une seule étape est active à la fois. */
  status: string;
}

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

  /**
   * Plan de travail posé ou mis à jour par l'agent (F-39 / SF-39-13). Porte la liste **complète** à
   * chaque appel : elle remplace la précédente. Additif — un backend antérieur ne l'émet pas.
   */
  onPlan?: (steps: AtelierPlanStep[]) => void;
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
  /**
   * Délai au bout duquel la demande expire, en millisecondes (F-47 / SF-47-02). **Additif** : le
   * flux du bac à sable (F-33) ne le porte pas — le délai y appartient au fournisseur, et inventer
   * une valeur serait pire que de n'en donner aucune. Absent ⇒ aucun compte à rebours affiché.
   */
  timeoutMs?: number;
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

  /**
   * Autorise **toutes** les commandes de ce message (F-38 / SF-38-20).
   *
   * <p>La portée est le **tour**, jamais le projet : le message suivant redemandera. C'est ce qui
   * distingue un raccourci d'un renoncement — on autorise ce qu'on a commencé à voir. Champ
   * additif : un backend antérieur l'ignore.</p>
   */
  allowAll?: boolean;
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
   * Plan de travail posé ou mis à jour par l'agent (F-39 / SF-39-13). Porte la liste **complète** à
   * chaque appel : elle remplace la précédente. Additif — un backend antérieur ne l'émet pas.
   */
  onPlan?: (steps: AtelierPlanStep[]) => void;
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

/**
 * Un projet vu **depuis son poste** (F-49 / SF-49-01). Fragment de la réponse de
 * `GET /api/runner-hosts/overview`.
 *
 * <p>`lastTool` est le **nom** du dernier outil employé (`bash`, `read`…), jamais sa cible : savoir
 * qu'un `bash` a tourné suffit à une vue d'état, et la commande elle-même reste derrière l'écran du
 * journal du projet.</p>
 */
export interface HostProjectSummary {
  id: string;
  name: string;
  /** Chemin du projet **sous la racine du poste**, ou `null` pour la racine elle-même. */
  projectPath?: string | null;
  executionTarget?: WorkspaceExecutionTarget | null;
  lastActivityAt?: string | null;
  lastTool?: string | null;
  /** Appels journalisés sur la fenêtre observée par la gateway. */
  calls: number;
  /** Vrai si ce projet a travaillé à l'instant — « ce qui tourne ». */
  active: boolean;
  /**
   * Vrai si un **terminal est ouvert** sur ce projet maintenant (F-70 / SF-70-01). À ne pas
   * confondre avec `active` : celui-ci dit qu'une commande a **tourné** récemment, celui-là qu'un
   * onglet **vit**. Un terminal peut vivre sans rien exécuter, et une commande peut avoir tourné
   * dans un onglet depuis refermé.
   */
  liveTerminal?: boolean;
}

/**
 * **Vue d'ensemble d'un poste** (F-49 / SF-49-01) : tout ce que l'écran des postes doit savoir d'une
 * machine, en une seule ligne de réponse.
 *
 * <p>Elle réunit ce qui vivait à trois endroits — l'état du runner, la liste des projets et le
 * journal de chacun. La gateway rend des **instants** (`lastSeenAt`, `lastActivityAt`) et l'écran en
 * fait des durées : une durée calculée au serveur vieillit dans le navigateur.</p>
 */
export interface RunnerHostOverview {
  /**
   * `null` pour le poste **virtuel** « Hébergé » (F-71 / SF-71-01) : il n'existe **aucune ligne en
   * base** pour lui, et donc aucun identifiant à envoyer nulle part. Nul par choix, pas par
   * omission — un identifiant constant finirait envoyé à un endpoint qui répondrait 404.
   */
  id: string | null;
  name: string;
  /**
   * Vrai pour le poste **« Hébergé »** (F-71 / SF-71-01), qui regroupe les projets sans machine —
   * dépôt GitHub, archive importée. **Ce n'est pas un poste** : ni appairage, ni runner, ni
   * suppression, ni état de mission. Il n'apparaît que s'il porte quelque chose.
   */
  virtual?: boolean;
  /** Dernier segment de la racine déclarée (ex. `dev`), jamais le chemin absolu de la machine. */
  rootName?: string | null;
  os?: string | null;
  /** `posix`, `powershell` ou `cmd`, ou `null` si aucun runner ne l'a déclaré. */
  shell?: string | null;
  elevated?: boolean | null;
  connected: boolean;
  /**
   * **État de mission** déclaré (F-60) — `ACTIVE`, `PENDING`, `CLOSED`. La gateway rend **tous**
   * les postes, clôturés compris : « se ranger sans disparaître » est une affaire d'écran, et
   * c'est `/postes` qui met les missions closes dans un repli plutôt que de les perdre.
   */
  missionStatus?: HostMissionStatus | null;
  lastSeenAt?: string | null;
  /** `null` pour le poste « Hébergé » (F-71) : rien n'a été créé, il n'a pas de date. */
  createdAt: string | null;
  /** Dernière activité observée sur le poste, tous projets confondus. */
  lastActivityAt?: string | null;
  /** Nombre de projets actifs maintenant — « ce qui tourne ». */
  activeProjects: number;
  /**
   * Nombre de **terminaux vivants** sur ce poste (F-70 / SF-70-01), **terminal du poste compris**
   * (F-74) : un onglet ouvert dessus tient une place dans le plafond de quatre comme un autre.
   */
  liveTerminals?: number;
  /**
   * Identifiant du **terminal du poste** (F-74 / SF-74-01), ou `null` s'il n'a jamais été ouvert.
   *
   * <p>L'écran n'en a pas besoin pour **proposer** le geste — l'endpoint retrouve ou crée — mais il
   * lui faut pour savoir de quel terminal on parle, et donc s'il vit.</p>
   */
  hostTerminalId?: string | null;
  /** Vrai si un onglet vit sur le terminal du poste **maintenant** (F-70 / F-74). */
  hostTerminalLive?: boolean;
  projects: HostProjectSummary[];
}

/**
 * Un **dossier** proposé au clic sous la racine d'un poste (F-71 / SF-71-02).
 *
 * <p>Taper un chemin à la main créait un projet vide qui n'échouait qu'au **premier usage**, quand
 * plus personne ne fait le lien avec la faute de frappe. Le runner liste, on clique.</p>
 */
export interface HostFolder {
  /** Nom du dossier, tel qu'il est sur la machine. */
  name: string;
  /** Chemin sous la racine du poste — la valeur à envoyer au rattachement. */
  path: string;
  /**
   * Vrai si un projet occupe **déjà** ce dossier. L'écran le marque et ne le propose pas : ouvrir
   * deux fois le même dossier a déjà produit deux entités du même nom.
   */
  used: boolean;
}

/** Réponse de `GET /api/runner-hosts/{id}/folders` (F-71 / SF-71-02). */
export interface HostFoldersResponse {
  /** Chemin parcouru, relatif à la racine ; chaîne vide = la racine elle-même. */
  path: string;
  /** Chemin du dossier parent, ou `null` à la racine — c'est ce qui permet de remonter. */
  parentPath: string | null;
  folders: HostFolder[];
  /**
   * Vrai si des dossiers **manquent** : la machine a tronqué sa liste, ou le plafond de la gateway
   * est atteint. Une liste incomplète se **dit** (SF-38-21).
   */
  truncated: boolean;
}

/**
 * Un **terminal vivant** (F-70 / SF-70-01) : un onglet ouvert, nommé de façon à ce que l'écran
 * puisse dire **lequel fermer**.
 */
export interface LiveTerminalEntry {
  workspaceId: string;
  workspaceName?: string | null;
  hostId?: string | null;
  hostName?: string | null;
  openedAt: string;
}

/**
 * **L'état du registre des terminaux vivants** (F-70 / SF-70-01).
 *
 * <p>Le PO a tranché **quatre au maximum**, et l'écran doit dire ce que cela engage : quatre flux
 * vivants, ce sont quatre consommations simultanées — quatre tours facturés en parallèle. `limit`
 * n'est donc pas un détail technique, c'est le garde-fou qu'on affiche.</p>
 */
export interface LiveTerminals {
  limit: number;
  live: number;
  terminals: LiveTerminalEntry[];
}
