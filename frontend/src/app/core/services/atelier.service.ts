import { HttpClient } from '@angular/common/http';
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
  AtelierMessage,
  AtelierResume,
  AtelierStreamAction,
  AtelierStreamHandlers,
  CreateGitWorkspaceRequest,
  ExecutionTargetRequest,
  FileContent,
  GitBranches,
  GitCommitResult,
  GitPullRequestRequest,
  GitPullRequestResult,
  GitPushRequest,
  GitPushResult,
  RunnerAuditEntry,
  RunnerKillResult,
  RunnerPairingCode,
  RunnerStatus,
  WorkspaceDetail,
  WorkspaceExecutionTarget,
  WorkspaceSummary,
  WriteFileRequest,
} from '../models/atelier.models';

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

  /** Parse un événement SSE (`event:` + `data:`) et route vers le bon callback. */
  private dispatchSseEvent(raw: string, handlers: AtelierStreamHandlers): void {
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
    let payload: Partial<AtelierStreamAction> & { text?: string; error?: string } & {
      reply?: string;
      actions?: AtelierChatResponse['actions'];
      messageId?: string;
      output?: string;
      toolUseId?: string;
      tool?: string;
      detail?: string;
      decision?: string;
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
      });
    } else if (event === 'confirm_resolved') {
      handlers.onConfirmResolved?.({
        toolUseId: payload.toolUseId ?? '',
        decision: payload.decision === 'deny' || payload.decision === 'timeout'
          ? payload.decision
          : 'allow',
      });
    } else if (event === 'done') {
      handlers.onDone({
        reply: payload.reply ?? '',
        actions: payload.actions ?? [],
        messageId: payload.messageId ?? '',
      });
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
   * Répond à une demande d'autorisation du mode **Assistant** (F-38 / SF-38-08) : autorise la
   * commande sur la machine connectée, ou la refuse avec un motif que le modèle recevra. Sans
   * réponse dans le délai imparti, le backend refuse — le silence ne vaut pas autorisation.
   */
  confirmChatToolUse(id: string, decision: AtelierConfirmDecision): Observable<void> {
    return this.http.post<void>(`/api/workspaces/${id}/chat/confirm`, decision);
  }

  /**
   * **Coupe-circuit** (F-38 / SF-38-08) : révoque tous les jetons runner du projet, coupe la
   * liaison en cours et ramène la cible d'exécution à `SANDBOX`. Idempotent — couper une liaison
   * déjà coupée n'est pas une erreur.
   */
  killRunner(id: string): Observable<RunnerKillResult> {
    return this.http.post<RunnerKillResult>(`/api/workspaces/${id}/runner/kill`, null);
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
   * État runner du projet (F-38 / SF-38-02). Volontairement **relevé à la demande** : aucun canal
   * poussé n'est ouvert pour cette information, et le backend lui-même la calcule avec une tolérance
   * de 90 s sur le dernier heartbeat — un temps réel affiché serait un mensonge.
   */
  getRunnerStatus(id: string): Observable<RunnerStatus> {
    return this.http.get<RunnerStatus>(`/api/workspaces/${id}/runner/status`);
  }

  /**
   * Génère un **code d'appairage à usage unique** pour ce projet (F-38 / SF-38-01). Le code
   * n'apparaît que dans cette réponse : il n'est ni stocké ni ré-obtenable, et une régénération
   * produit un nouveau code.
   */
  createRunnerPairingCode(id: string): Observable<RunnerPairingCode> {
    return this.http.post<RunnerPairingCode>(`/api/workspaces/${id}/runner/pairing-code`, null);
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
}
