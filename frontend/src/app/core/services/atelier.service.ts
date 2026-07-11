import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AtelierChatRequest,
  AtelierChatResponse,
  AtelierMessage,
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

  /** Envoie un message ; Claude lit/édite les fichiers via une boucle tool-use côté backend. */
  chat(id: string, message: string): Observable<AtelierChatResponse> {
    const body: AtelierChatRequest = { message };
    return this.http.post<AtelierChatResponse>(`/api/workspaces/${id}/chat`, body);
  }

  /** Historique de conversation du workspace. */
  getHistory(id: string): Observable<AtelierMessage[]> {
    return this.http.get<AtelierMessage[]>(`/api/workspaces/${id}/chat`);
  }
}
