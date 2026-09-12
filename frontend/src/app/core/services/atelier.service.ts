import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from './auth.service';
import {
  AtelierAgentStreamAction,
  AtelierFileDiff,
  AtelierAgentStreamHandlers,
  AtelierChatRequest,
  AtelierConfirmDecision,
  AtelierConfirmationState,
  AtelierChatResponse,
  AtelierEngineStatus,
  AtelierMessage,
  AtelierResume,
  AtelierStreamAction,
  AtelierStreamHandlers,
  AtelierTurnState,
  CreateGitWorkspaceRequest,
  ExecutionTargetRequest,
  FileContent,
  GitBranches,
  GitCommitResult,
  GitPullRequestRequest,
  GitPullRequestResult,
  GitPushRequest,
  GitPushResult,
  AttachHostRequest,
  HostFoldersResponse,
  HostMissionRequest,
  HostMissionStatus,
  RunnerAuditEntry,
  RunnerHost,
  RunnerHostOverview,
  RunnerHostRequest,
  RunnerKillResult,
  ProxyRelayFormats,
  RunnerDownloadFormats,
  RunnerPairingCode,
  RunnerStatus,
  WorkspaceDetail,
  WorkspaceExecutionTarget,
  WorkspaceSummary,
  WriteFileRequest,
  AtelierPlanStep,
} from '../models/atelier.models';

/**
 * Plateformes pour lesquelles la gateway sert le relais `px` (F-59 / SF-59-01).
 *
 * macOS Intel n'y figure pas : le projet amont ne publie pas ce binaire, et en fabriquer un
 * reviendrait à maintenir une version autre que celle publiée — hors périmètre. Le chemin `pip3` y
 * reste proposé.
 */
export type ProxyRelayPlatform = 'windows' | 'macos-aarch64' | 'linux-x64';

/** Ce que sert cette gateway, lu avant d'afficher le moindre lien (jamais un lien mort). */
export const PROXY_RELAY_FORMATS_PATH = '/api/runner/relay/formats';

/**
 * Notice **MIT** de `px`, affichable telle quelle. C'est la condition de sa redistribution, et
 * l'écran doit pouvoir la montrer **avant** de faire télécharger 21 Mo.
 */
export const PROXY_RELAY_LICENSE_PATH = '/api/runner/relay/license';

/** Chemin de téléchargement du relais pour une plateforme — une route par plateforme (D1, F-44). */
export function proxyRelayDownloadPath(platform: ProxyRelayPlatform): string {
  return `/api/runner/relay/${platform}`;
}

/**
 * Accès à l'API de l'Atelier (F-28 « Claude Code Lite »). Le frontend ne communique qu'avec la
 * Gateway (`/api/...`), jamais directement avec un fournisseur IA. L'isolation des données est
 * garantie côté backend via le `user_id` porté par le JWT (ajouté par l'`authInterceptor`).
 */
@Injectable({ providedIn: 'root' })
export class AtelierService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /** Crée un workspace à partir d'une archive `.zip` (multipart, champ `file`, `name` optionnel). */
  createWorkspace(file: File, name?: string): Observable<WorkspaceDetail> {
    const form = new FormData();
    form.append('file', file);
    if (name) {
      form.append('name', name);
    }
    return this.http.post<WorkspaceDetail>('/api/workspaces', form);
  }

  /**
   * Ouvre un projet qui vit **déjà sur la machine** de l'utilisateur (F-38 / SF-38-15).
   *
   * <p>Le corps ne porte qu'un **nom** : aucun chemin n'est transmis, et aucun ne le sera. Un
   * navigateur ne peut pas donner un chemin du disque, et la gateway n'a aucune raison de connaître
   * l'arborescence de la machine — le dossier se désigne au lancement du runner.</p>
   */
  createLocalWorkspace(name: string): Observable<WorkspaceDetail> {
    return this.http.post<WorkspaceDetail>('/api/workspaces/local', { name });
  }

  /**
   * Ouvre un projet sur un dépôt GitHub (F-31 / SF-31-02). Aucun secret ne transite : le jeton
   * d'accès a été enregistré séparément (réglages) et n'est manipulé que côté backend.
   */
  createGitWorkspace(request: CreateGitWorkspaceRequest): Observable<WorkspaceDetail> {
    return this.http.post<WorkspaceDetail>('/api/workspaces/git', request);
  }

  /**
   * Publie le travail de la session sur une branche dédiée (F-31 / SF-31-04) et renvoie le lien
   * d'ouverture de pull request. Réponse `200` même si rien n'a été poussé : `pushed` dit ce qui
   * s'est réellement passé, et `reply` en donne la cause.
   */
  pushBranch(id: string, request: GitPushRequest): Observable<GitPushResult> {
    return this.http.post<GitPushResult>(`/api/workspaces/${id}/git/push`, request);
  }

  /**
   * Ouvre la pull request de la branche publiée (F-31 / SF-31-05). Réponse `200` même si rien n'a
   * été ouvert : `created` dit ce qui s'est réellement passé — constaté auprès de GitHub, pas déduit
   * de ce que l'agent répond — et `reply` en donne la cause.
   */
  createPullRequest(id: string, request: GitPullRequestRequest): Observable<GitPullRequestResult> {
    return this.http.post<GitPullRequestResult>(`/api/workspaces/${id}/git/pull-request`, request);
  }

  /** Workspaces de l'utilisateur. */
  listWorkspaces(): Observable<WorkspaceSummary[]> {
    return this.http.get<WorkspaceSummary[]>('/api/workspaces');
  }

  /** Détail d'un workspace : métadonnées + arborescence des fichiers. */
  getWorkspace(id: string): Observable<WorkspaceDetail> {
    return this.http.get<WorkspaceDetail>(`/api/workspaces/${id}`);
  }

  /** Contenu texte d'un fichier du workspace. */
  getFile(id: string, path: string): Observable<FileContent> {
    return this.http.get<FileContent>(`/api/workspaces/${id}/file`, { params: { path } });
  }

  /** Écrit (remplace) le contenu texte d'un fichier du workspace. */
  /** Branches du dépôt, avec celle du projet et celle par défaut (F-31 / SF-31-10). */
  gitBranches(id: string): Observable<GitBranches> {
    return this.http.get<GitBranches>(`/api/workspaces/${id}/git/branches`);
  }

  /** Place le projet sur une branche existante (F-31 / SF-31-10). */
  switchGitBranch(id: string, branch: string): Observable<WorkspaceDetail> {
    return this.http.put<WorkspaceDetail>(`/api/workspaces/${id}/git/branch`, { branch });
  }

  /** Crée une branche depuis celle du projet et s'y place (F-31 / SF-31-10). */
  createGitBranch(id: string, branch: string): Observable<WorkspaceDetail> {
    return this.http.post<WorkspaceDetail>(`/api/workspaces/${id}/git/branches`, { branch });
  }

  /**
   * Publie les modifications faites par l'utilisateur en un commit sur une branche dédiée
   * (F-31 / SF-31-09, endpoint de SF-31-08). Un seul appel porte tous les fichiers : le commit est
   * atomique côté serveur.
   */
  commitGitFiles(id: string, branch: string, message: string): Observable<GitCommitResult> {
    // Le serveur publie tout le travail non publié du projet : l'écran n'envoie pas de contenus.
    return this.http.post<GitCommitResult>(`/api/workspaces/${id}/git/commit`, { branch, message });
  }

  writeFile(id: string, path: string, content: string): Observable<void> {
    const body: WriteFileRequest = { content };
    return this.http.put<void>(`/api/workspaces/${id}/file`, body, { params: { path } });
  }

  /** Supprime un fichier du workspace (RGPD/gestion, SF-28-14). Renvoie 204 (pas de corps). */
  deleteFile(id: string, path: string): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${id}/file`, { params: { path } });
  }

  /**
   * Renomme (ou déplace) un fichier du workspace (SF-28-14) : `from` → `to`. Le backend valide les
   * chemins (`invalid_file_path`) sous double filtre `user_id` et renvoie l'arborescence à jour.
   */
  renameFile(id: string, from: string, to: string): Observable<WorkspaceDetail> {
    return this.http.post<WorkspaceDetail>(`/api/workspaces/${id}/file/rename`, { from, to });
  }

  /** Exporte tout le workspace en archive `.zip` (SF-28-14) : réponse binaire (`application/zip`). */
  exportZip(id: string): Observable<Blob> {
    return this.http.get(`/api/workspaces/${id}/export`, { responseType: 'blob' });
  }

  /**
   * Importe le texte de documents de la bibliothèque personnelle (F-08) dans le workspace
   * (SF-28-13). Chaque document est écrit sous `bibliotheque/<nom>.md` côté backend, qui relit les
   * documents sous double filtre `user_id` (isolation) et renvoie l'arborescence à jour.
   */
  importLibrary(id: string, documentIds: string[]): Observable<WorkspaceDetail> {
    return this.http.post<WorkspaceDetail>(`/api/workspaces/${id}/import-library`, { documentIds });
  }

  /** Envoie un message ; Claude lit/édite les fichiers via une boucle tool-use côté backend. */
  chat(id: string, message: string): Observable<AtelierChatResponse> {
    const body: AtelierChatRequest = { message };
    return this.http.post<AtelierChatResponse>(`/api/workspaces/${id}/chat`, body);
  }

  /**
   * Envoie un message en **streaming** (SF-28-05) : consomme le flux SSE de
   * `POST /api/workspaces/{id}/chat/stream` via `fetch` + `ReadableStream` (EventSource ne supporte
   * pas POST). Relaie chaque étape (`onAction`), le commentaire de tour (`onText`), puis `onDone`
   * (réponse finale + actions) ; toute erreur (HTTP ou `event:error`) appelle `onError`. Ne lève
   * jamais : les échecs passent par `onError`.
   */
  async streamChat(id: string, message: string, handlers: AtelierStreamHandlers): Promise<void> {
    try {
      const token = this.auth.token();
      const response = await fetch(`/api/workspaces/${id}/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ message }),
      });
      if (!response.ok || !response.body) {
        handlers.onError('request_failed');
        return;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        let sep: number;
        while ((sep = buffer.indexOf('\n\n')) >= 0) {
          this.dispatchSseEvent(buffer.slice(0, sep), handlers);
          buffer = buffer.slice(sep + 2);
        }
      }
    } catch {
      handlers.onError('request_failed');
    }
  }

  /**
   * **Se rebrancher** sur le tour en cours d'un projet (F-84 / SF-84-02).
   *
   * Rouvrir le terminal ne relance rien : l'écran rejoue ce qu'il a manqué depuis `cursor`, puis
   * reprend le direct. `cursor = 0` — le cas d'un écran neuf — veut dire « je n'ai rien vu ».
   *
   * Rend un `AbortController` : quitter l'écran **détache le spectateur**, et c'est tout. Depuis
   * F-84, abandonner ce flux n'arrête plus le tour — c'était précisément le défaut.
   */
  attachTurn(id: string, cursor: number, handlers: AtelierStreamHandlers): AbortController {
    const abort = new AbortController();
    void this.readTurnStream(id, cursor, handlers, abort);
    return abort;
  }

  /** L'état du tour d'un projet : est-ce que ça tourne, et à quel curseur (F-84 / SF-84-02). */
  getTurnState(id: string): Observable<AtelierTurnState> {
    return this.http.get<AtelierTurnState>(`/api/workspaces/${id}/chat/turn`);
  }

  /** Lit le flux de rebranchement ; un abandon (écran quitté) n'est jamais une erreur à signaler. */
  private async readTurnStream(
    id: string,
    cursor: number,
    handlers: AtelierStreamHandlers,
    abort: AbortController,
  ): Promise<void> {
    try {
      const token = this.auth.token();
      const response = await fetch(
        `/api/workspaces/${id}/chat/attach?cursor=${encodeURIComponent(String(cursor))}`,
        {
          method: 'GET',
          headers: {
            Accept: 'text/event-stream',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          signal: abort.signal,
        },
      );
      if (!response.ok || !response.body) {
        // Se rebrancher est un CONFORT : échouer ici ne doit pas afficher une panne sur un écran
        // qui, par ailleurs, fonctionne. On reste simplement sans direct.
        handlers.onIdle?.();
        return;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        let sep: number;
        while ((sep = buffer.indexOf('\n\n')) >= 0) {
          this.dispatchSseEvent(buffer.slice(0, sep), handlers);
          buffer = buffer.slice(sep + 2);
        }
      }
    } catch {
      // Abandon volontaire (écran quitté) ou réseau coupé : dans les deux cas, plus de direct.
      handlers.onIdle?.();
    }
  }

  /** Parse un événement SSE (`event:` + `data:`) et route vers le bon callback. */
  private dispatchSseEvent(raw: string, handlers: AtelierStreamHandlers): void {
    let event = 'message';
    let data = '';
    let id = '';
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice('event:'.length).trim();
      } else if (line.startsWith('data:')) {
        data += line.slice('data:'.length).trim();
      } else if (line.startsWith('id:')) {
        // Le numéro d'ordre de l'événement (F-84 / SF-84-02) : c'est le curseur à renvoyer pour se
        // rebrancher. Les apartés de branchement portent 0 et ne le font jamais avancer.
        id = line.slice('id:'.length).trim();
      }
    }
    if (!data) {
      return;
    }
    const seq = Number(id);
    if (Number.isFinite(seq) && seq > 0) {
      handlers.onSeq?.(seq);
    }
    let payload: Partial<AtelierStreamAction> & { text?: string; error?: string } & {
      reply?: string;
      actions?: AtelierChatResponse['actions'];
      messageId?: string;
      output?: string;
      toolUseId?: string;
      tool?: string;
      detail?: string;
      decision?: string;
      /** Délai d'expiration d'une demande d'autorisation, en ms (F-47 / SF-47-02). */
      timeoutMs?: number;
      tokens?: number;
      inputTokens?: number;
      outputTokens?: number;
      activeSeconds?: number;
      budgetReached?: boolean;
      /** Plan de travail relayé au fil de l'eau (F-39 / SF-39-13). */
      steps?: AtelierPlanStep[];
      /** Rebranchement sur un tour en cours (F-84 / SF-84-02). */
      turnId?: string | null;
      cursor?: number;
      startedAt?: number;
      droppedThrough?: number;
    };
    try {
      payload = JSON.parse(data);
    } catch {
      return;
    }
    if (event === 'action') {
      handlers.onAction({ type: payload.type ?? 'read', path: payload.path });
    } else if (event === 'output') {
      // Sortie d'une commande exécutée sur la machine connectée (F-38 / SF-38-07). Additif : un
      // backend antérieur ne l'émet pas, et un appelant qui ne s'y abonne pas l'ignore.
      handlers.onOutput?.(payload.output ?? '');
    } else if (event === 'text') {
      handlers.onText(payload.text ?? '');
    } else if (event === 'confirm_request') {
      // L'agent attend une autorisation avant d'exécuter sur la machine (F-38 / SF-38-08) : le
      // tour est en pause tant que rien n'est décidé.
      handlers.onConfirmRequest?.({
        toolUseId: payload.toolUseId ?? '',
        tool: payload.tool ?? '',
        detail: payload.detail ?? '',
        // Délai relayé par la gateway (F-47 / SF-47-02). La clé n'est posée que si elle vaut
        // quelque chose : tout ce qui n'est pas un nombre strictement positif est traité comme
        // absent — mieux vaut aucun compte à rebours qu'un compte à rebours faux.
        ...(typeof payload.timeoutMs === 'number' && payload.timeoutMs > 0
          ? { timeoutMs: payload.timeoutMs }
          : {}),
      });
    } else if (event === 'confirm_state') {
      // Ce que le tour attend À L'INSTANT (F-84 / SF-84-03). Il arrive après le rejeu, et son
      // `timeoutMs` est le TEMPS RESTANT calculé par la gateway : c'est lui qui corrige le compte à
      // rebours, là où le `confirm_request` rejoué annoncerait encore le délai d'origine (SF-47-02).
      // Routé vers la même invite : pour l'écran, une attente est une attente.
      handlers.onConfirmRequest?.({
        toolUseId: payload.toolUseId ?? '',
        tool: payload.tool ?? '',
        detail: payload.detail ?? '',
        ...(typeof payload.timeoutMs === 'number' && payload.timeoutMs > 0
          ? { timeoutMs: payload.timeoutMs }
          : {}),
      });
    } else if (event === 'confirm_resolved') {
      handlers.onConfirmResolved?.({
        toolUseId: payload.toolUseId ?? '',
        decision: payload.decision === 'deny' || payload.decision === 'timeout'
          ? payload.decision
          : 'allow',
      });
    } else if (event === 'progress') {
      // Consommation cumulée du tour (F-39 / SF-39-15). Additif : un backend antérieur ne l'émet
      // pas, et un appelant qui ne s'y abonne pas l'ignore.
      handlers.onProgress?.(payload.tokens ?? 0);
    } else if (event === 'plan') {
      // Plan de travail du tour (F-39 / SF-39-13) : la liste complète, qui remplace la précédente.
      handlers.onPlan?.(payload.steps ?? []);
    } else if (event === 'done') {
      handlers.onDone({
        reply: payload.reply ?? '',
        actions: payload.actions ?? [],
        messageId: payload.messageId ?? '',
        // Champs additifs (F-39 / SF-39-15) : laissés `undefined` par un backend antérieur, pour
        // que l'écran n'affiche aucun coût plutôt qu'un « 0 token » qui passerait pour une mesure.
        inputTokens: payload.inputTokens,
        outputTokens: payload.outputTokens,
        activeSeconds: payload.activeSeconds,
        budgetReached: payload.budgetReached === true,
      });
    } else if (event === 'attached') {
      // L'écran s'est rebranché sur un tour en cours (F-84 / SF-84-02) : ce qui suit est le rejeu.
      handlers.onAttached?.({
        turnId: payload.turnId ?? null,
        cursor: typeof payload.cursor === 'number' ? payload.cursor : 0,
        startedAt: typeof payload.startedAt === 'number' ? payload.startedAt : 0,
      });
    } else if (event === 'idle') {
      // Rien ne tourne : l'état d'avant F-84, dit explicitement plutôt que deviné.
      handlers.onIdle?.();
    } else if (event === 'truncated') {
      handlers.onTruncated?.(
        typeof payload.droppedThrough === 'number' ? payload.droppedThrough : 0,
      );
    } else if (event === 'error') {
      handlers.onError(payload.error ?? 'provider_error');
    }
  }

  /**
   * Envoie un message en mode **Exécution** (Phase 2, SF-28-11) : consomme le flux SSE de
   * `POST /api/workspaces/{id}/agent/stream` via `fetch` + `ReadableStream`, sur le même modèle que
   * {@link streamChat}. L'agent Managed d'Anthropic exécute la tâche (bash, tests, build) dans un
   * sandbox hébergé ; on relaie le commentaire (`onAgent`), chaque étape d'outil (`onAction`), la
   * **sortie** de chaque commande (`onActionResult`, F-30), les changements d'état (`onStatus`), puis `onDone` (réponse finale + fichiers modifiés). Toute erreur
   * (HTTP ou `event:error`) appelle `onError`. Ne lève jamais : les échecs passent par `onError`.
   */
  async streamAgent(id: string, message: string, handlers: AtelierAgentStreamHandlers): Promise<void> {
    try {
      const token = this.auth.token();
      const response = await fetch(`/api/workspaces/${id}/agent/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ message }),
      });
      if (!response.ok || !response.body) {
        handlers.onError('request_failed');
        return;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        let sep: number;
        while ((sep = buffer.indexOf('\n\n')) >= 0) {
          this.dispatchAgentSseEvent(buffer.slice(0, sep), handlers);
          buffer = buffer.slice(sep + 2);
        }
      }
    } catch {
      handlers.onError('request_failed');
    }
  }

  /** Parse un événement SSE du mode Exécution (`event:` + `data:`) et route vers le bon callback. */
  private dispatchAgentSseEvent(raw: string, handlers: AtelierAgentStreamHandlers): void {
    let event = 'message';
    let data = '';
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice('event:'.length).trim();
      } else if (line.startsWith('data:')) {
        data += line.slice('data:'.length).trim();
      }
    }
    if (!data) {
      return;
    }
    let payload: Partial<AtelierAgentStreamAction> & {
      text?: string;
      state?: string;
      reply?: string;
      changedFiles?: string[];
      error?: string | boolean;
      toolUseId?: string | null;
      threadId?: string | null;
      output?: string;
      inputTokens?: number;
      outputTokens?: number;
      activeSeconds?: number;
      interrupted?: boolean;
      budgetReached?: boolean;
      diffs?: AtelierFileDiff[];
      decision?: string;
      /** Délai d'expiration d'une demande d'autorisation, en ms (F-47 / SF-47-02). */
      timeoutMs?: number;
      tokens?: number;
    };
    try {
      payload = JSON.parse(data);
    } catch {
      return;
    }
    if (event === 'agent') {
      handlers.onAgent(payload.text ?? '');
    } else if (event === 'action') {
      handlers.onAction({
        tool: payload.tool ?? '',
        detail: payload.detail,
        toolUseId: payload.toolUseId ?? null,
        // Champ additif (F-35 SF-35-02) : absent d'un backend antérieur ⇒ run séquentiel.
        threadId: payload.threadId ?? null,
      });
    } else if (event === 'action_result') {
      // Sortie de la commande (F-30) : c'est elle qui fait le rendu terminal.
      handlers.onActionResult({
        tool: payload.tool ?? '',
        toolUseId: payload.toolUseId ?? null,
        output: payload.output ?? '',
        error: payload.error === true,
        threadId: payload.threadId ?? null,
      });
    } else if (event === 'progress') {
      // Consommation du tour en cours (F-30 / SF-30-13). Additif : un backend antérieur ne l'émet
      // pas, et un appelant qui ne s'y abonne pas ne voit aucune différence.
      handlers.onProgress?.(payload.tokens ?? 0);
    } else if (event === 'status') {
      handlers.onStatus(payload.state ?? '');
    } else if (event === 'done') {
      handlers.onDone({
        reply: payload.reply ?? '',
        changedFiles: payload.changedFiles ?? [],
        inputTokens: payload.inputTokens ?? 0,
        outputTokens: payload.outputTokens ?? 0,
        activeSeconds: payload.activeSeconds ?? 0,
        // Champ additif (F-32 SF-32-01) : absent d'un backend antérieur ⇒ tour non interrompu.
        interrupted: payload.interrupted === true,
        // Idem pour le plafond de dépense du run (F-36 SF-36-01) : sans ce relais, la mention
        // n'apparaissait qu'après rechargement de la page, relue du tour persisté.
        budgetReached: payload.budgetReached === true,
        // Modifications du tour (F-37 SF-37-01) : liste vide quand le backend ne les envoie pas.
        diffs: payload.diffs ?? [],
      });
    } else if (event === 'confirm_request') {
      // L'agent attend une autorisation (F-33) : la session est en pause tant que rien n'est décidé.
      handlers.onConfirmRequest?.({
        toolUseId: payload.toolUseId ?? '',
        tool: payload.tool ?? '',
        detail: payload.detail ?? '',
        // Délai relayé par la gateway (F-47 / SF-47-02). La clé n'est posée que si elle vaut
        // quelque chose : tout ce qui n'est pas un nombre strictement positif est traité comme
        // absent — mieux vaut aucun compte à rebours qu'un compte à rebours faux.
        ...(typeof payload.timeoutMs === 'number' && payload.timeoutMs > 0
          ? { timeoutMs: payload.timeoutMs }
          : {}),
      });
    } else if (event === 'confirm_resolved') {
      handlers.onConfirmResolved?.({
        toolUseId: payload.toolUseId ?? '',
        decision: payload.decision === 'deny' || payload.decision === 'timeout'
          ? payload.decision
          : 'allow',
      });
    } else if (event === 'error') {
      handlers.onError(typeof payload.error === 'string' ? payload.error : 'provider_error');
    }
  }

  /**
   * Termine la session sandbox du workspace (F-30 SF-30-06) : le message suivant repartira d'un
   * environnement neuf. Les fichiers du projet ne sont pas touchés.
   */
  resetAgentSession(id: string): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${id}/agent/session`);
  }

  /**
   * Demande l'**interruption** du run en cours (F-32 SF-32-02). L'arrêt est asynchrone : la session
   * s'arrête à une frontière sûre côté fournisseur, et c'est le flux SSE en cours qui se clôt par son
   * `done` — cet appel dit seulement que la demande est partie.
   */
  interruptAgentSession(id: string): Observable<void> {
    return this.http.post<void>(`/api/workspaces/${id}/agent/interrupt`, null);
  }

  /**
   * Dépose une **précision** pour le tour en cours (F-39 / SF-39-19).
   *
   * <p>Ce n'est pas une interruption : rien ne s'arrête. L'agent la lira au début de son itération
   * suivante et en tiendra compte pour la suite.</p>
   */
  steerChat(id: string, message: string): Observable<void> {
    return this.http.post<void>(`/api/workspaces/${id}/chat/steer`, { message });
  }

  /**
   * Répond à une demande d'autorisation du mode **Assistant** (F-38 / SF-38-08) : autorise la
   * commande sur la machine connectée, ou la refuse avec un motif que le modèle recevra. Sans
   * réponse dans le délai imparti, le backend refuse — le silence ne vaut pas autorisation.
   */
  confirmChatToolUse(id: string, decision: AtelierConfirmDecision): Observable<void> {
    return this.http.post<void>(`/api/workspaces/${id}/chat/confirm`, decision);
  }

  /**
   * **Coupe-circuit** d'un **poste** (F-38 / SF-38-08, porté à la machine par F-48 / SF-48-01) :
   * révoque tous ses jetons runner, coupe la liaison en cours, et ramène **tous les projets** de
   * cette machine à la cible `SANDBOX`.
   *
   * <p>On ne coupe pas un dossier, on coupe une machine : ne ramener qu'un projet au bac à sable
   * laisserait les autres pointer vers un runner mort. Idempotent — couper une liaison déjà coupée
   * n'est pas une erreur.</p>
   */
  killHost(hostId: string): Observable<RunnerKillResult> {
    return this.http.post<RunnerKillResult>(`/api/runner-hosts/${hostId}/kill`, null);
  }

  /** Journal d'activité du runner (F-38 / SF-38-08), du plus récent au plus ancien. */
  getRunnerAudit(id: string, limit?: number): Observable<RunnerAuditEntry[]> {
    return this.http.get<RunnerAuditEntry[]>(`/api/workspaces/${id}/runner/audit`,
      limit ? { params: { limit } } : {});
  }

  /**
   * Interrompt le tour du mode **Assistant** en cours (F-38 / SF-38-07). En cible `RUNNER`, la
   * commande lancée sur la machine de l'utilisateur est tuée et la boucle s'arrête à la frontière
   * sûre suivante. Idempotent : interrompre alors que rien ne tourne renvoie 204.
   */
  interruptChat(id: string): Observable<void> {
    return this.http.post<void>(`/api/workspaces/${id}/chat/interrupt`, null);
  }

  /**
   * Active ou désactive la **demande d'autorisation avant exécution** pour ce projet (F-33 / SF-33-01).
   * La politique est fixée à l'ouverture de la sandbox : `appliesToCurrentSession` dit si le réglage
   * vaut déjà pour celle en cours, ou seulement pour la prochaine.
   */
  setAskBeforeBash(id: string, enabled: boolean): Observable<AtelierConfirmationState> {
    return this.http.put<AtelierConfirmationState>(
      `/api/workspaces/${id}/agent/confirmation`, { enabled });
  }

  /**
   * Répond à une demande d'autorisation (F-33 / SF-33-02) : autorise la commande, ou la refuse avec
   * un motif que l'agent recevra. Sans réponse dans le délai imparti, le backend refuse — le silence
   * ne vaut pas autorisation.
   */
  confirmToolUse(id: string, decision: AtelierConfirmDecision): Observable<void> {
    return this.http.post<void>(`/api/workspaces/${id}/agent/confirm`, decision);
  }

  /**
   * Renomme le projet (F-28 SF-28-16). Le nom est une étiquette : ni les fichiers, ni la session
   * sandbox, ni l'historique ne bougent.
   */
  renameWorkspace(id: string, name: string): Observable<WorkspaceDetail> {
    return this.http.post<WorkspaceDetail>(`/api/workspaces/${id}/rename`, { name });
  }

  /**
   * **Supprime le projet** (F-28, exposé à l'écran par F-69 / SF-69-02).
   *
   * <p><b>Côté gateway, et uniquement là</b> : la conversation, son historique, les réglages, le
   * journal d'exécution et les fichiers importés dans la gateway. <b>Le dossier sur la machine de
   * l'utilisateur n'est jamais touché</b> — la gateway n'émet aucune commande vers le runner sur ce
   * chemin. C'est une limite de périmètre tranchée par le PO, et le dialogue de confirmation
   * l'écrit avant de demander quoi que ce soit.</p>
   */
  deleteWorkspace(id: string): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${id}`);
  }

  /** Historique de conversation du workspace. */
  getHistory(id: string): Observable<AtelierMessage[]> {
    return this.http.get<AtelierMessage[]>(`/api/workspaces/${id}/chat`);
  }

  /**
   * État de reprise du fil (F-39 / SF-39-04). Appelé à l'ouverture d'un projet : il ne sert qu'à
   * savoir s'il faut, exceptionnellement, proposer un choix — la reprise, elle, est silencieuse.
   */
  getResume(id: string): Observable<AtelierResume> {
    return this.http.get<AtelierResume>(`/api/workspaces/${id}/chat/resume`);
  }

  /**
   * Nouveau départ (F-39 / SF-39-04) : les tours passés cessent d'être rejoués. **Rien n'est
   * supprimé** — la conversation reste affichée, seule la mémoire de l'agent repart de zéro.
   */
  restartThread(id: string): Observable<AtelierResume> {
    return this.http.post<AtelierResume>(`/api/workspaces/${id}/chat/restart`, null);
  }

  /**
   * Bascule la **cible d'exécution** du projet (F-38 / SF-38-05) : `SANDBOX` (sandbox hébergé) ou
   * `RUNNER` (la machine de l'utilisateur, via le runner local). Renvoie le détail à jour — c'est
   * lui qui fait foi, jamais la valeur demandée : la cible pilote où s'écrivent réellement les
   * fichiers, l'afficher de façon optimiste serait dangereux.
   */
  setExecutionTarget(id: string, target: WorkspaceExecutionTarget): Observable<WorkspaceDetail> {
    const body: ExecutionTargetRequest = { executionTarget: target };
    return this.http.put<WorkspaceDetail>(`/api/workspaces/${id}/execution-target`, body);
  }

  /**
   * Moteur qui anime le terminal de ce projet (F-39 / SF-39-07). L'écran le **lit** : depuis le
   * lot 4, plus aucune règle de moteur ne vit côté Angular — elle avait déjà été inversée une fois
   * entre F-31 et F-38 sans qu'aucun test ne puisse le dire.
   */
  getEngine(id: string): Observable<AtelierEngineStatus> {
    return this.http.get<AtelierEngineStatus>(`/api/workspaces/${id}/engine`);
  }

  /**
   * État runner du projet (F-38 / SF-38-02). Volontairement **relevé à la demande** : aucun canal
   * poussé n'est ouvert pour cette information, et le backend lui-même la calcule avec une tolérance
   * de 90 s sur le dernier heartbeat — un temps réel affiché serait un mensonge.
   */
  getRunnerStatus(id: string): Observable<RunnerStatus> {
    return this.http.get<RunnerStatus>(`/api/workspaces/${id}/runner/status`);
  }

  /**
   * État runner d'un **poste** (F-48 / SF-48-01), relevé par le parcours de mise en service quand
   * il part de la **machine** et non d'un projet (F-72 / SF-72-02).
   *
   * <p>C'est le même relevé, à la même tolérance de 90 s, mais posé sur la bonne ressource : quand
   * on vient de connecter un poste, <b>aucun projet n'existe encore</b> — et c'est normal. Demander
   * l'état d'un projet qui n'existe pas n'aurait pas de réponse.</p>
   */
  getHostRunnerStatus(hostId: string): Observable<RunnerStatus> {
    return this.http.get<RunnerStatus>(`/api/runner-hosts/${hostId}/status`);
  }

  /**
   * Génère le **code d'appairage à usage unique** d'un **poste** (F-38 / SF-38-01, porté à la
   * machine par F-48 / SF-48-01).
   *
   * <p><b>Un seul par machine</b> : c'est tout l'objet de F-48. Ouvrir un projet de plus sous la
   * racine du poste ne demande ni code, ni runner, ni connexion supplémentaires. Le code n'apparaît
   * que dans cette réponse : il n'est ni stocké ni ré-obtenable, et une régénération en produit un
   * nouveau.</p>
   */
  createHostPairingCode(hostId: string): Observable<RunnerPairingCode> {
    return this.http.post<RunnerPairingCode>(`/api/runner-hosts/${hostId}/pairing-code`, null);
  }

  /** Postes de l'utilisateur (F-48 / SF-48-01), avec leur état de connexion. */
  listRunnerHosts(): Observable<RunnerHost[]> {
    return this.http.get<RunnerHost[]>('/api/runner-hosts');
  }

  /**
   * **Vue d'ensemble** des postes (F-49 / SF-49-01) : en un seul appel, chaque machine, son état,
   * les projets rangés dessous et ce qui tourne sur chacun.
   *
   * <p>C'est une **lecture**, rejouée à intervalle par l'écran des postes — jamais un canal. Des
   * flux vivants simultanés multiplieraient les tours facturés pour un bénéfice que l'état couvre
   * déjà (arbitrage n° 3 du cadrage du 2026-09-10).</p>
   *
   * <p>L'appel ne porte **aucun identifiant** : la gateway part du JWT, et l'écran ne peut donc pas
   * demander la machine d'un autre compte.</p>
   */
  runnerHostsOverview(): Observable<RunnerHostOverview[]> {
    return this.http.get<RunnerHostOverview[]>('/api/runner-hosts/overview');
  }

  /**
   * Déclare l'**état de mission** d'un poste (F-60 / SF-60-01) : en cours, en attente, clôturé.
   *
   * <p>Rend le poste **à jour** — c'est lui qui fait foi, jamais la valeur demandée. L'écran ne
   * change donc pas d'état de façon optimiste : clôturer *range* une carte hors de la vue
   * principale, et la faire disparaître avant de savoir si l'ordre a abouti la ferait réapparaître
   * à la relecture suivante.</p>
   *
   * <p><b>Ce geste ne coupe rien</b> côté gateway : ni jeton, ni liaison, ni rattachement de
   * projet, ni journal. Couper une machine reste le coupe-circuit, et il est ailleurs.</p>
   */
  setHostMissionStatus(hostId: string, status: HostMissionStatus): Observable<RunnerHost> {
    return this.http.put<RunnerHost>(`/api/runner-hosts/${hostId}/mission`,
      { missionStatus: status } satisfies HostMissionRequest);
  }

  /** Crée un poste au nom libre (F-48 / SF-48-01). */
  createRunnerHost(name: string): Observable<RunnerHost> {
    return this.http.post<RunnerHost>('/api/runner-hosts', { name } satisfies RunnerHostRequest);
  }

  /**
   * **Supprime un poste** (F-69 / SF-69-02) : son appairage, ses jetons et sa ligne.
   *
   * <p><b>Refusé par la gateway (409) tant qu'il porte des projets</b> — pas de cascade, décision du
   * PO. L'écran dit le même refus avant le clic pour ne pas faire cliquer sur un bouton qui va
   * refuser, mais c'est le serveur qui fait foi : un projet créé dans un autre onglet et l'écran a
   * tort.</p>
   *
   * <p>La <b>machine</b>, elle, n'est pas touchée : ni ses fichiers, ni le runner installé dessus.
   * Elle cesse simplement d'être connue de la gateway.</p>
   */
  deleteRunnerHost(hostId: string): Observable<void> {
    return this.http.delete<void>(`/api/runner-hosts/${hostId}`);
  }

  /**
   * **Sous-dossiers d'un poste** (F-71 / SF-71-02) : ce qu'on **clique** pour désigner le dossier
   * d'un projet, au lieu de le taper.
   *
   * <p>La lecture se fait **sur la machine**, par le runner. Elle échoue donc en **409** quand
   * aucun runner n'est connecté — un état, pas une panne, et l'écran doit le **dire** plutôt que
   * d'afficher une liste vide qui ferait croire à une racine sans sous-dossier.</p>
   *
   * @param path chemin relatif sous la racine ; absent = la racine elle-même
   */
  runnerHostFolders(hostId: string, path?: string): Observable<HostFoldersResponse> {
    const params = path ? new HttpParams().set('path', path) : undefined;
    return this.http.get<HostFoldersResponse>(`/api/runner-hosts/${hostId}/folders`, { params });
  }

  /**
   * **Ouvre un projet sur un dossier du poste** (F-72 / SF-72-01) — le second des deux gestes.
   *
   * <p>Un seul appel crée le projet <b>et</b> le rattache, et <b>aucun nom n'est demandé</b> : le
   * projet prend celui de son dossier, celui du poste pour la racine. Le nom a déjà été donné une
   * fois, à la connexion du poste.</p>
   *
   * <p>Le doublon est <b>refusé</b> par la gateway (409 `host_project_exists`) : ouvrir deux fois
   * le même dossier a déjà produit deux entités du même nom.</p>
   *
   * @param path chemin relatif sous la racine ; chaîne vide = la racine du poste
   */
  openHostProject(hostId: string, path: string): Observable<WorkspaceDetail> {
    return this.http.post<WorkspaceDetail>(`/api/runner-hosts/${hostId}/projects`, { path });
  }

  /**
   * **Le terminal du poste** (F-74 / SF-74-01) : celui qui existe, ou celui qu'on crée.
   *
   * <p>Ce qu'il débloque : le premier jour d'une mission, la racine est **vide** — pas de projet,
   * donc pas de terminal, donc aucun moyen de cloner un dépôt depuis le produit. Et au-delà, `git`,
   * un VPN, `terraform`, l'installation d'un outil n'appartiennent à aucun projet.</p>
   *
   * <p>**Idempotent** : la gateway répond `200` dans les deux cas et l'écran n'a pas à distinguer
   * « créé » de « retrouvé » — il demande le terminal de ce poste, il le reçoit, il l'ouvre.</p>
   */
  openHostTerminal(hostId: string): Observable<WorkspaceDetail> {
    return this.http.post<WorkspaceDetail>(`/api/runner-hosts/${hostId}/terminal`, null);
  }

  /**
   * **Rattache** un projet à un poste (F-48 / SF-48-01) : la machine qui l'exécute, et son chemin
   * relatif sous la racine de cette machine. `hostId` à `null` détache le projet.
   *
   * <p>C'est le geste qui remplace l'appairage par dossier — un poste appairé une fois accueille
   * autant de projets qu'il porte de sous-dossiers.</p>
   */
  attachWorkspaceToHost(id: string, hostId: string | null,
    projectPath?: string): Observable<WorkspaceDetail> {
    return this.http.put<WorkspaceDetail>(`/api/workspaces/${id}/host`,
      { hostId, projectPath } satisfies AttachHostRequest);
  }

  /**
   * Télécharge le binaire du runner (F-38 / SF-38-03). Endpoint **public** : le jar est un client,
   * il ne porte aucun secret. Un **404** n'est pas une panne mais un état de déploiement normal —
   * `app.runner.jar-path` est vide par défaut, le jar n'étant pas empaqueté dans l'image ; l'appelant
   * doit alors proposer la commande de construction plutôt qu'une erreur technique.
   */
  downloadRunnerJar(): Observable<Blob> {
    return this.http.get('/api/runner/download', { responseType: 'blob' });
  }

  /**
   * Télécharge le **paquet autonome Windows** (F-44 / SF-44-02) : le runner accompagné de sa propre
   * JVM, pour les postes où aucun Java 21 n'est installable. Endpoint public comme le jar.
   */
  downloadRunnerWindowsPackage(): Observable<Blob> {
    return this.http.get('/api/runner/download/windows', { responseType: 'blob' });
  }

  /**
   * Télécharge le **paquet autonome macOS** (F-44 / SF-44-03), pour l'architecture demandée. Même
   * nature que le paquet Windows : le runner et sa propre JVM, pour un poste Mac d'entreprise sans
   * droits administrateur ni JDK.
   *
   * <p>Côté API, ce sont bien **deux routes** distinctes (D1) ; l'argument n'est que la plomberie
   * qui choisit laquelle appeler.</p>
   */
  downloadRunnerMacosPackage(arch: 'aarch64' | 'x64'): Observable<Blob> {
    return this.http.get(`/api/runner/download/macos-${arch}`, { responseType: 'blob' });
  }

  /**
   * Formats de runner réellement disponibles sur cette gateway (F-44 / SF-44-02, étendu par
   * SF-44-03). L'écran les lit pour **masquer** un format absent plutôt que d'offrir un lien qui
   * répondrait 404 — une gateway déployée avant F-44 n'empaquette aucun paquet, une gateway
   * déployée entre SF-44-02 et SF-44-03 n'a que celui de Windows.
   */
  runnerDownloadFormats(): Observable<RunnerDownloadFormats> {
    return this.http.get<RunnerDownloadFormats>('/api/runner/download/formats');
  }

  /**
   * Relais `px` servis par **cette** gateway (F-59 / SF-59-01), et version amont servie.
   *
   * <p>Lu par l'assistant proxy pour proposer **notre domaine d'abord** — le seul dont on soit sûr
   * qu'il est autorisé chez le client — et masquer le lien quand la gateway ne sert rien, plutôt
   * que d'en offrir un mort (même règle qu'en F-44).</p>
   */
  proxyRelayFormats(): Observable<ProxyRelayFormats> {
    return this.http.get<ProxyRelayFormats>(PROXY_RELAY_FORMATS_PATH);
  }

  /**
   * Télécharge le relais `px` pour une plateforme donnée. Endpoint **public** : c'est un binaire
   * tiers, sans jeton ni secret — et celui qui en a besoin est justement celui dont le poste ne sort
   * pas.
   */
  downloadProxyRelay(platform: ProxyRelayPlatform): Observable<Blob> {
    return this.http.get(proxyRelayDownloadPath(platform), { responseType: 'blob' });
  }
}
