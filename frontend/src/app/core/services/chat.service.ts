import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from './auth.service';
import {
  ChatRequest,
  ChatResponse,
  ConversationDetail,
  ConversationSummary,
  ModelsResponse,
  RenameConversationRequest,
} from '../models/chat.models';

/** Métadonnées de fin de flux de chat (événement SSE `done`). */
export interface ChatStreamDone {
  conversationId: string;
  messageId: string;
  model: string;
}

/** Callbacks du streaming de chat (SF-02-05). */
export interface ChatStreamHandlers {
  onToken: (text: string) => void;
  onDone: (done: ChatStreamDone) => void;
  onError: (code: string) => void;
}

/**
 * Accès à l'API de chat F-02. Le frontend ne communique qu'avec Claude Gateway (`/api/...`),
 * jamais directement avec un fournisseur IA. L'isolation des données est garantie côté backend
 * via le `user_id` porté par le JWT (ajouté par l'`authInterceptor`).
 */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /** Envoie un message ; crée la conversation si `conversationId` est absent (non-streamé, conservé). */
  sendMessage(body: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/chat', body);
  }

  /**
   * Envoie un message en **streaming** (SF-02-05) : consomme le flux SSE de `POST /api/chat/stream`
   * via `fetch` + `ReadableStream` (EventSource ne supporte pas POST). Relaie chaque fragment
   * (`onToken`), puis `onDone` (conversationId/messageId/model) ; toute erreur (HTTP ou `event:error`)
   * appelle `onError`. Ne lève jamais : les échecs passent par `onError`.
   */
  async streamMessage(body: ChatRequest, handlers: ChatStreamHandlers): Promise<void> {
    try {
      const token = this.auth.token();
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(body),
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
  private dispatchSseEvent(raw: string, handlers: ChatStreamHandlers): void {
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
    let payload: { text?: string; error?: string } & Partial<ChatStreamDone>;
    try {
      payload = JSON.parse(data);
    } catch {
      return;
    }
    if (event === 'token') {
      handlers.onToken(payload.text ?? '');
    } else if (event === 'done') {
      handlers.onDone({
        conversationId: payload.conversationId ?? '',
        messageId: payload.messageId ?? '',
        model: payload.model ?? '',
      });
    } else if (event === 'error') {
      handlers.onError(payload.error ?? 'provider_error');
    }
  }

  /** Modèles sélectionnables + modèle par défaut. */
  getModels(): Observable<ModelsResponse> {
    return this.http.get<ModelsResponse>('/api/chat/models');
  }

  /** Conversations de l'utilisateur, les plus récemment actives d'abord. */
  listConversations(): Observable<ConversationSummary[]> {
    return this.http.get<ConversationSummary[]>('/api/conversations');
  }

  /** Détail d'une conversation (métadonnées + fil de messages). */
  getConversation(id: string): Observable<ConversationDetail> {
    return this.http.get<ConversationDetail>(`/api/conversations/${id}`);
  }

  /** Renomme une conversation. */
  renameConversation(id: string, title: string): Observable<ConversationSummary> {
    const body: RenameConversationRequest = { title };
    return this.http.patch<ConversationSummary>(`/api/conversations/${id}`, body);
  }

  /** Supprime une conversation (et ses messages). */
  deleteConversation(id: string): Observable<void> {
    return this.http.delete<void>(`/api/conversations/${id}`);
  }
}
