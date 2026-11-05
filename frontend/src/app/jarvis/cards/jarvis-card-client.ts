import { Injectable, Injector, inject } from '@angular/core';

import { AuthService } from '../../core/services/auth.service';
import { CardField, JarvisCard } from './jarvis-card.models';

/** F-248 / SF-248-06 — le détail d'une pancarte (contrat de `GET /api/jarvis/cards/{id}`). */
export interface JarvisCardDetail {
  card: JarvisCard;
  fields: CardField[];
  previous?: { value: string; at?: string | null } | null;
  subjectId?: string | null;
  subjectName?: string | null;
  chantier?: number | null;
}

/** Le chemin d'une pancarte : l'identifiant encodé, les deux-points gardés (`fait:attente:…`). */
export function cardPath(id: string): string {
  return `/api/jarvis/cards/${encodeURIComponent(id).replace(/%3A/gi, ':')}`;
}

/**
 * F-248 / SF-248-06 — **lire le détail d'une pancarte à l'ouverture** (D6) : une ligne par index côté passerelle,
 * filtrée par compte. Ne lève jamais : `null` si absente (404), éteinte (503) ou injoignable.
 */
@Injectable({ providedIn: 'root' })
export class JarvisCardClient {
  /** Paresseux : l'écran `/jarvis` ne résout l'authentification qu'à la première ouverture. */
  private readonly injector = inject(Injector);

  async detail(id: string, signal?: AbortSignal): Promise<JarvisCardDetail | null> {
    try {
      const token = this.injector.get(AuthService).token();
      const response = await fetch(cardPath(id), {
        headers: token ? { Authorization: `Bearer ${token}` } : {}, signal,
      });
      if (!response.ok) {
        return null;
      }
      return await response.json() as JarvisCardDetail;
    } catch {
      return null;
    }
  }
}
