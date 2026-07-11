import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from './auth.service';
import {
  AtelierAgentStreamAction,
  AtelierAgentStreamHandlers,
  AtelierChatRequest,
  AtelierChatResponse,
  AtelierMessage,
  AtelierStreamAction,
  AtelierStreamHandlers,
  FileContent,
  WorkspaceDetail,
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
  writeFile(id: string, path: string, content: string): Observable<void> {
    const body: WriteFileRequest = { content };
    return this.http.put<void>(`/api/workspaces/${id}/file`, body, { params: { path } });
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
    };
    try {
      payload = JSON.parse(data);
    } catch {
      return;
    }
    if (event === 'action') {
      handlers.onAction({ type: payload.type ?? 'read', path: payload.path });
    } else if (event === 'text') {
      handlers.onText(payload.text ?? '');
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
   * sandbox hébergé ; on relaie le commentaire (`onAgent`), chaque étape d'outil (`onAction`), les
   * changements d'état (`onStatus`), puis `onDone` (réponse finale + fichiers modifiés). Toute erreur
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
      error?: string;
    };
    try {
      payload = JSON.parse(data);
    } catch {
      return;
    }
    if (event === 'agent') {
      handlers.onAgent(payload.text ?? '');
    } else if (event === 'action') {
      handlers.onAction({ tool: payload.tool ?? '', detail: payload.detail });
    } else if (event === 'status') {
      handlers.onStatus(payload.state ?? '');
    } else if (event === 'done') {
      handlers.onDone({ reply: payload.reply ?? '', changedFiles: payload.changedFiles ?? [] });
    } else if (event === 'error') {
      handlers.onError(payload.error ?? 'provider_error');
    }
  }

  /** Historique de conversation du workspace. */
  getHistory(id: string): Observable<AtelierMessage[]> {
    return this.http.get<AtelierMessage[]>(`/api/workspaces/${id}/chat`);
  }
}
