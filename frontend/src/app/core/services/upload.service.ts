import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { UploadedFileResponse } from '../models/upload.models';

/**
 * Accès à l'API d'upload F-04. Le frontend ne communique qu'avec Claude Gateway (`/api/upload`),
 * jamais directement avec un fournisseur IA. L'isolation des données est garantie côté backend
 * via le `user_id` porté par le JWT (ajouté par l'`authInterceptor`).
 */
@Injectable({ providedIn: 'root' })
export class UploadService {
  private readonly http = inject(HttpClient);

  /** Téléverse un fichier (multipart) et renvoie ses métadonnées. */
  uploadFile(file: File): Observable<UploadedFileResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<UploadedFileResponse>('/api/upload', formData);
  }
}
