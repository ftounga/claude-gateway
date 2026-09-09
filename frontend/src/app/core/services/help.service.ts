import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Réponse du chatbot d'aide produit (F-54). */
export interface HelpChatResponse {
  answer: string;
}

/**
 * Accès à l'aide produit F-54. Le frontend ne communique qu'avec Claude Gateway (`/api/...`).
 * L'identité est portée par le JWT ajouté par l'`authInterceptor` : aucun identifiant utilisateur
 * n'est transmis par le client, et aucune donnée de l'utilisateur n'est envoyée — seulement sa
 * question.
 */
@Injectable({ providedIn: 'root' })
export class HelpService {
  private readonly http = inject(HttpClient);

  /** Pose une question sur l'usage du produit. */
  chat(message: string): Observable<HelpChatResponse> {
    return this.http.post<HelpChatResponse>('/api/help/chat', { message });
  }
}
