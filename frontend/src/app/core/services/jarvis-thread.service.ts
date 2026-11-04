import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AtelierMessageLink } from '../models/atelier.models';

/** Le fil Jarvis du compte (F-245 / SF-245-01) : `GET /api/jarvis/thread`. */
export interface JarvisThread {
  id: string;
  name: string;
}

/** F-245 / SF-245-04 — une ligne du fil du jour (`GET /api/jarvis/thread/today`). */
export interface JarvisThreadItem {
  id: string;
  role: 'USER' | 'ASSISTANT' | 'LINK';
  /** La parole, la ligne 🎙 de la réponse (≤ 300 caractères) ou le texte de la ligne-lien. */
  text: string;
  at: string;
  /** Vrai pour un échange du cerveau rapide (réponse « Jarvis »). */
  fast: boolean;
  link: AtelierMessageLink | null;
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

  /** F-245 / SF-245-04 — le fil du jour (lecture seule : le serveur ne crée rien). */
  today(): Observable<JarvisThreadItem[]> {
    return this.http.get<JarvisThreadItem[]>('/api/jarvis/thread/today');
  }
}
