import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Le fil Jarvis du compte (F-245 / SF-245-01) : `GET /api/jarvis/thread`. */
export interface JarvisThread {
  id: string;
  name: string;
}

/**
 * **Le fil Jarvis** (F-245 / SF-245-01, D1) : un espace par utilisateur, créé par le serveur à la première demande.
 * Le compte est celui du principal — aucun paramètre ne désigne un utilisateur.
 */
@Injectable({ providedIn: 'root' })
export class JarvisThreadService {
  private readonly http = inject(HttpClient);

  /** Le fil du compte, créé s'il n'existe pas encore (idempotent côté serveur). */
  open(): Observable<JarvisThread> {
    return this.http.get<JarvisThread>('/api/jarvis/thread');
  }
}
