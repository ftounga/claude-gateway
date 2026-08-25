import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { GitTokenStatus, SaveGitTokenRequest } from '../models/git-token.models';

/**
 * Accès à l'API du jeton GitHub (F-31 / SF-31-01). Le frontend ne dialogue qu'avec Claude Gateway
 * (`/api/...`) ; l'isolation est garantie côté backend via le `user_id` porté par le JWT (ajouté par
 * l'`authInterceptor`). Aucun `user_id` n'est jamais transmis par le client, et le jeton en clair
 * n'est jamais renvoyé par le backend.
 */
@Injectable({ providedIn: 'root' })
export class GitTokenService {
  private readonly http = inject(HttpClient);

  /** État du jeton de l'utilisateur (présent/absent, masqué, compte GitHub). */
  getStatus(): Observable<GitTokenStatus> {
    return this.http.get<GitTokenStatus>('/api/user/git-token');
  }

  /** Enregistre ou remplace le jeton (vérifié auprès de GitHub puis chiffré côté backend). */
  saveToken(request: SaveGitTokenRequest): Observable<GitTokenStatus> {
    return this.http.post<GitTokenStatus>('/api/user/git-token', request);
  }

  /** Retire le jeton de l'utilisateur. */
  deleteToken(): Observable<void> {
    return this.http.delete<void>('/api/user/git-token');
  }
}
