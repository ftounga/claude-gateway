import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { MessageSend } from '../models/message-sends.models';

/**
 * « Marquer envoyé » sur un encart mail/message (F-211 / SF-211-02). Un geste, sans tour : le
 * serveur note l'envoi, fait passer l'attente liée à « Demandé » et le dira à l'agent au tour suivant.
 */
@Injectable({ providedIn: 'root' })
export class MessageSendsService {
  private readonly http = inject(HttpClient);

  /** Les envois marqués du terminal, relus à son ouverture. */
  list(workspaceId: string): Observable<MessageSend[]> {
    return this.http.get<MessageSend[]>(`/api/workspaces/${workspaceId}/messages/sends`);
  }

  /** [Marquer envoyé] le `index`-ième bloc mail/message de la réponse `messageId`. */
  mark(workspaceId: string, messageId: string, index: number): Observable<MessageSend> {
    return this.http.post<MessageSend>(
      `/api/workspaces/${workspaceId}/messages/${messageId}/sends/${index}`, null);
  }

  /** [Annuler] l'envoi marqué. */
  cancel(workspaceId: string, messageId: string, index: number): Observable<void> {
    return this.http.delete<void>(`/api/workspaces/${workspaceId}/messages/${messageId}/sends/${index}`);
  }
}
