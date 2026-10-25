import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Réponse de `POST /api/workspaces/{id}/enrich-prompt` (F-193 / SF-193-01, contrat figé). */
export interface EnrichPromptResponse {
  /** La demande structurée, prête à relire et à éditer. Rien n'est envoyé. */
  enrichedPrompt: string;
  /** Ce que le modèle a supposé, à confirmer. */
  toConfirm: string[];
  /** Ce qui a été lu pour enrichir, en toutes lettres. */
  sources: string[];
  /** F-215 / SF-215-05 : `video` pour un cahier de réalisation, `prompt` sinon (absent : ancien serveur). */
  mode?: 'video' | 'prompt';
}

/**
 * **Plume** (F-193 / SF-193-02) — l'appel d'enrichissement d'une demande, sur geste de l'utilisateur.
 * Le serveur borne la matière, appelle le fournisseur et compose la demande ; l'écran ne fait que la
 * proposer dans la saisie.
 */
@Injectable({ providedIn: 'root' })
export class PlumeService {
  private readonly http = inject(HttpClient);

  enrich(workspaceId: string, text: string): Observable<EnrichPromptResponse> {
    return this.http.post<EnrichPromptResponse>(`/api/workspaces/${workspaceId}/enrich-prompt`, { text });
  }
}
