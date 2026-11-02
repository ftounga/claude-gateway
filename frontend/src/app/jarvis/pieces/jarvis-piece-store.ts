import { Injectable, computed, inject, signal } from '@angular/core';

import { JarvisPieceClient, PieceQuery } from './jarvis-piece-client';
import { NavAction, NavOrigin, NavReason, NavScope, PieceNav, initialNav, reduceNav, selectedItem } from './jarvis-piece-nav';
import { Piece } from './jarvis-piece.models';

/** Refus de la galerie : ceux du réducteur, plus le mode présentation et l'index injoignable. */
export type PieceStoreReason = NavReason | 'PRESENTATION' | 'UNAVAILABLE';

export interface PieceStoreResult {
  ok: boolean;
  reason?: PieceStoreReason;
}

/**
 * F-237 / SF-237-05 — **l'état des pièces à l'écran** : l'état de navigation (réducteur pur SF-237-04) et les pièces
 * chargées. Une seule porte d'entrée (`dispatch`) pour le toucher, la voix locale et, plus tard, l'outil `afficher`.
 * **Mode présentation** (D11, Q1) : aucune pièce n'est rendue, toute ouverture est refusée.
 */
@Injectable({ providedIn: 'root' })
export class JarvisPieceStore {
  private readonly client = inject(JarvisPieceClient);
  private loading: AbortController | null = null;

  readonly nav = signal<PieceNav>(initialNav());
  readonly pieces = signal<ReadonlyMap<string, Piece>>(new Map());
  readonly presentation = signal(false);
  /** Une galerie ou une pièce est ouverte. */
  readonly open = computed(() => {
    const level = this.nav().level;
    return level !== 'UNIVERS' && level !== 'CHANTIER';
  });
  /** La pièce héros, avec ses métadonnées complètes. */
  readonly selected = computed<Piece | null>(() => {
    const item = selectedItem(this.nav());
    return item ? this.pieces().get(item.id) ?? null : null;
  });

  /** Applique une action ; en présentation, seule la fermeture passe. */
  dispatch(action: NavAction): PieceStoreResult {
    if (this.presentation() && action.type !== 'close') {
      return { ok: false, reason: 'PRESENTATION' };
    }
    const result = reduceNav(this.nav(), action);
    if (result.ok) {
      this.nav.set(result.state);
    }
    return result.ok ? { ok: true } : { ok: false, reason: result.reason };
  }

  /** Ouvre une galerie : lit l'index (annule la lecture précédente), puis l'état passe en GALERIE. */
  async openGallery(scope: NavScope, query: PieceQuery, origin: NavOrigin = 'ASKED'): Promise<PieceStoreResult> {
    if (this.presentation()) {
      return { ok: false, reason: 'PRESENTATION' };
    }
    this.loading?.abort();
    const controller = new AbortController();
    this.loading = controller;
    const response = await this.client.list(query, controller.signal);
    if (controller.signal.aborted) {
      return { ok: false, reason: 'UNAVAILABLE' };
    }
    this.loading = null;
    if (!response) {
      return { ok: false, reason: 'UNAVAILABLE' };
    }
    return this.show(scope, response.pieces, origin);
  }

  /** Montre des pièces déjà en main (pièce produite en direct, tests). */
  show(scope: NavScope, pieces: readonly Piece[], origin: NavOrigin = 'ASKED'): PieceStoreResult {
    if (this.presentation()) {
      return { ok: false, reason: 'PRESENTATION' };
    }
    const map = new Map<string, Piece>();
    for (const piece of pieces) {
      map.set(piece.id, piece);
    }
    this.pieces.set(map);
    return this.dispatch({ type: 'openGallery', scope, pieces, origin });
  }

  /** Tout ferme (sortie de l'écran, bascule en présentation). */
  reset(): void {
    this.loading?.abort();
    this.loading = null;
    this.nav.set(initialNav());
    this.pieces.set(new Map());
  }
}

/** Ce que Jarvis dit d'un refus — court, jamais technique. */
export function refusalPhrase(reason: PieceStoreReason | undefined): string {
  switch (reason) {
    case 'OUT_OF_RANGE':
      return 'Il n’y en a pas d’autre.';
    case 'NOT_APPLICABLE':
      return 'Ce n’est pas possible sur cette pièce.';
    case 'NO_GALLERY':
      return 'Aucune galerie n’est ouverte.';
    case 'NOT_FOUND':
      return 'Je ne la trouve pas.';
    case 'PRESENTATION':
      return 'Pas de pièce en mode présentation.';
    default:
      return 'Je n’arrive pas à lire les pièces pour le moment.';
  }
}
