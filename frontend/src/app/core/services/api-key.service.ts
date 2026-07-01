import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiKeyStatus, SaveApiKeyRequest, SetModeRequest } from '../models/api-key.models';

/**
 * Accès à l'API de gestion de la clé BYOK (F-03). Le frontend ne dialogue qu'avec Claude Gateway
 * (`/api/...`) ; l'isolation est garantie côté backend via le `user_id` porté par le JWT (ajouté par
 * l'`authInterceptor`). Aucun `user_id` n'est jamais transmis par le client, et la clé en clair n'est
 * jamais renvoyée.
 */
@Injectable({ providedIn: 'root' })
export class ApiKeyService {
  private readonly http = inject(HttpClient);

  /** Statut de la clé de l'utilisateur (présente/absente, masquée, mode). */
  getStatus(): Observable<ApiKeyStatus> {
    return this.http.get<ApiKeyStatus>('/api/user/api-key');
  }

  /** Ajoute/remplace la clé (validée puis chiffrée côté backend). Renvoie le statut masqué. */
  saveKey(request: SaveApiKeyRequest): Observable<ApiKeyStatus> {
    return this.http.post<ApiKeyStatus>('/api/user/api-key', request);
  }

  /** Supprime la clé de l'utilisateur. */
  deleteKey(): Observable<void> {
    return this.http.delete<void>('/api/user/api-key');
  }

  /** Bascule le mode fournisseur (Hosted/BYOK). Renvoie le statut à jour. */
  setMode(request: SetModeRequest): Observable<ApiKeyStatus> {
    return this.http.put<ApiKeyStatus>('/api/user/api-key/mode', request);
  }
}
