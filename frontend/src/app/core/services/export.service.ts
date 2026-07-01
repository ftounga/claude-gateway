import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AnswerExportRequest, ExportFormat } from '../models/export.models';

/**
 * Accès à l'API d'export F-14. Le frontend ne fait que demander le fichier à Claude Gateway
 * (`/api/...`) ; le rendu Markdown/PDF est produit côté backend. L'isolation des données est garantie
 * côté backend via le `user_id` porté par le JWT (ajouté par l'`authInterceptor`) : le service
 * n'envoie jamais d'identifiant utilisateur.
 */
@Injectable({ providedIn: 'root' })
export class ExportService {
  private readonly http = inject(HttpClient);

  /** Télécharge la conversation possédée au format demandé (réponse binaire + en-têtes). */
  exportConversation(conversationId: string, format: ExportFormat): Observable<HttpResponse<Blob>> {
    return this.http.get(`/api/conversations/${conversationId}/export`, {
      params: { format },
      responseType: 'blob',
      observe: 'response',
    });
  }

  /** Télécharge une réponse citée fournie par l'appelant (endpoint stateless). */
  exportAnswer(body: AnswerExportRequest, format: ExportFormat): Observable<HttpResponse<Blob>> {
    return this.http.post('/api/export/answer', body, {
      params: { format },
      responseType: 'blob',
      observe: 'response',
    });
  }

  /**
   * Déclenche le téléchargement navigateur à partir de la réponse HTTP : crée un lien `<a download>`
   * temporaire (nom de fichier issu de `Content-Disposition`, sinon `fallbackName`) puis révoque
   * l'URL objet. Aucune dépendance tierce.
   */
  triggerDownload(response: HttpResponse<Blob>, fallbackName: string): void {
    const blob = response.body;
    if (!blob) {
      return;
    }
    const filename = this.filenameFromDisposition(response.headers.get('Content-Disposition')) ?? fallbackName;
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  }

  /** Extrait le nom de fichier d'un en-tête `Content-Disposition`, ou `null` si absent. */
  private filenameFromDisposition(header: string | null): string | null {
    if (!header) {
      return null;
    }
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
    return match ? decodeURIComponent(match[1]) : null;
  }
}
