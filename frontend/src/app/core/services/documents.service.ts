import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { DocumentDetailResponse, DocumentResponse } from '../models/documents.models';

/**
 * Accès à l'API OCR documents (F-05). Le frontend ne communique qu'avec Claude Gateway
 * (`/api/documents`), jamais directement avec un fournisseur OCR/IA. L'isolation des données est
 * garantie côté backend via le `user_id` porté par le JWT (ajouté par l'`authInterceptor`).
 */
@Injectable({ providedIn: 'root' })
export class DocumentsService {
  private readonly http = inject(HttpClient);

  /** Soumet un document (multipart) pour extraction OCR et renvoie ses métadonnées. */
  submit(file: File): Observable<DocumentResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<DocumentResponse>('/api/documents', formData);
  }

  /** Liste des documents de l'utilisateur courant (les plus récents d'abord). */
  list(): Observable<DocumentResponse[]> {
    return this.http.get<DocumentResponse[]>('/api/documents');
  }

  /** Détail d'un document (texte extrait inclus). */
  get(id: string): Observable<DocumentDetailResponse> {
    return this.http.get<DocumentDetailResponse>(`/api/documents/${id}`);
  }
}
