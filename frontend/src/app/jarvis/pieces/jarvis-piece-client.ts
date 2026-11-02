import { Injectable, Injector, inject } from '@angular/core';

import { AuthService } from '../../core/services/auth.service';
import { PieceCategory, PieceKind, PieceListResponse } from './jarvis-piece.models';

/** Une lecture de l'index des pièces (SF-237-01). */
export interface PieceQuery {
  subjectId?: string | null;
  chantier?: number | null;
  category?: PieceCategory | null;
  kind?: PieceKind | null;
  series?: string | null;
  limit?: number;
}

/** La requête de l'index, sans paramètre vide. Exporté pour les tests. */
export function pieceQueryString(query: PieceQuery): string {
  const params = new URLSearchParams();
  if (query.subjectId) {
    params.set('subjectId', query.subjectId);
  }
  if (query.chantier != null) {
    params.set('chantier', String(query.chantier));
  }
  if (query.category) {
    params.set('category', query.category);
  }
  if (query.kind) {
    params.set('kind', query.kind);
  }
  if (query.series) {
    params.set('series', query.series);
  }
  params.set('limit', String(query.limit ?? 50));
  return params.toString();
}

/**
 * F-237 / SF-237-05 — lit l'index des pièces et leurs contenus **par la passerelle**, sous JWT (jamais une URL de
 * stockage) ; un contenu binaire devient une URL d'objet, à révoquer par l'appelant. Ne lève jamais.
 */
@Injectable({ providedIn: 'root' })
export class JarvisPieceClient {
  // Lue à l'usage : construire le client ne doit pas construire l'authentification (dépendance circulaire en test).
  private readonly injector = inject(Injector);

  async list(query: PieceQuery, signal?: AbortSignal): Promise<PieceListResponse | null> {
    try {
      const response = await fetch(`/api/jarvis/pieces?${pieceQueryString(query)}`, { headers: this.headers(), signal });
      return response.ok ? ((await response.json()) as PieceListResponse) : null;
    } catch {
      return null;
    }
  }

  /** Un contenu (chemin passerelle rendu par l'index) en URL d'objet, ou `null`. */
  async objectUrl(path: string | null | undefined, signal?: AbortSignal): Promise<string | null> {
    if (!path || !path.startsWith('/')) {
      return null;
    }
    try {
      const response = await fetch(`/api${path}`, { headers: this.headers(), signal });
      if (!response.ok) {
        return null;
      }
      return URL.createObjectURL(await response.blob());
    } catch {
      return null;
    }
  }

  /** Un contenu texte (corps d'un courriel). */
  async text(path: string | null | undefined, signal?: AbortSignal): Promise<string | null> {
    if (!path || !path.startsWith('/')) {
      return null;
    }
    try {
      const response = await fetch(`/api${path}`, { headers: this.headers(), signal });
      return response.ok ? await response.text() : null;
    } catch {
      return null;
    }
  }

  /** Un JSON passerelle (ticket de lecture d'une page, d'une vidéo). */
  async json<T>(path: string | null | undefined, signal?: AbortSignal): Promise<T | null> {
    if (!path || !path.startsWith('/')) {
      return null;
    }
    try {
      const response = await fetch(`/api${path}`, { headers: this.headers(), signal });
      return response.ok ? ((await response.json()) as T) : null;
    } catch {
      return null;
    }
  }

  private headers(): Record<string, string> {
    const token = this.injector.get(AuthService).token();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }
}
