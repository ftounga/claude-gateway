import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  ChatRequest,
  ChatResponse,
  ConversationDetail,
  ConversationSummary,
  ModelsResponse,
  RenameConversationRequest,
} from '../models/chat.models';

/**
 * Accès à l'API de chat F-02. Le frontend ne communique qu'avec Claude Gateway (`/api/...`),
 * jamais directement avec un fournisseur IA. L'isolation des données est garantie côté backend
 * via le `user_id` porté par le JWT (ajouté par l'`authInterceptor`).
 */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);

  /** Envoie un message ; crée la conversation si `conversationId` est absent. */
  sendMessage(body: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/chat', body);
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
