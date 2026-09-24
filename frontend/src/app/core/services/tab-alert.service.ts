import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

/**
 * Signal in-tab (F-153 / SF-153-01) — palier 2 de la « version mobile ».
 *
 * <p>Quand l'onglet est <b>caché</b> et qu'un tour <b>se termine</b> ou <b>passe en attente
 * d'autorisation</b> (états déjà exposés par F-84, cf. audit du 2026-09-24), on allume un signal
 * discret <b>dans l'onglet</b> : un préfixe de titre et un favicon dynamique. Au retour au premier
 * plan (`visibilitychange`), tout est rétabli. Première alerte, <b>sans PWA, quasi gratuite</b> ;
 * le Web Push (app fermée / téléphone verrouillé) viendra en SF-153-02/03.</p>
 *
 * <p><b>Gateway-First</b> : ce service ne recalcule aucun état, il se branche sur les transitions
 * déjà détectées par le terminal. Il ne lit aucune donnée métier — aucun contenu de tour, aucun
 * nom de projet : seulement un libellé neutre.</p>
 */
@Injectable({ providedIn: 'root' })
export class TabAlertService {
  private readonly doc = inject(DOCUMENT);

  /** Titre de l'onglet avant le signal, à restaurer. `null` = aucun signal levé. */
  private savedTitle: string | null = null;
  /** `href` du favicon avant le signal, à restaurer (`null` si aucun `<link rel="icon">`). */
  private savedFaviconHref: string | null = null;
  private raised = false;
  private listenerBound = false;

  /** Favicon d'alerte : pastille orange de marque (DESIGN_SYSTEM), en data-URI SVG. */
  private static readonly ALERT_FAVICON =
    'data:image/svg+xml,' +
    encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">' +
        '<rect width="32" height="32" rx="7" fill="#0B1020"/>' +
        '<circle cx="16" cy="16" r="8" fill="#E07B39"/>' +
        '</svg>',
    );

  /** Un tour s'est terminé : signale « Réponse prête » si l'onglet est caché. */
  signalTurnDone(): void {
    this.raise('Réponse prête');
  }

  /** Un tour attend une autorisation : signale « Autorisation demandée » si l'onglet est caché. */
  signalAwaitingAuthorization(): void {
    this.raise('Autorisation demandée');
  }

  /**
   * Lève le signal in-tab, mais UNIQUEMENT si l'onglet est caché : au premier plan, l'utilisateur
   * voit déjà la réponse ou l'invite d'autorisation à l'écran — un signal serait du bruit.
   */
  private raise(label: string): void {
    if (!this.doc.hidden) {
      return;
    }
    this.ensureListener();
    if (!this.raised) {
      this.savedTitle = this.doc.title;
      this.savedFaviconHref = this.faviconLink()?.getAttribute('href') ?? null;
    }
    this.doc.title = `● ${label} — ${this.savedTitle ?? this.doc.title}`;
    this.faviconLink()?.setAttribute('href', TabAlertService.ALERT_FAVICON);
    this.raised = true;
  }

  /** Rétablit le titre et le favicon d'origine. Idempotent (sans effet si aucun signal levé). */
  clear(): void {
    if (!this.raised) {
      return;
    }
    if (this.savedTitle !== null) {
      this.doc.title = this.savedTitle;
    }
    const link = this.faviconLink();
    if (link) {
      if (this.savedFaviconHref !== null) {
        link.setAttribute('href', this.savedFaviconHref);
      } else {
        link.removeAttribute('href');
      }
    }
    this.savedTitle = null;
    this.savedFaviconHref = null;
    this.raised = false;
  }

  private faviconLink(): HTMLLinkElement | null {
    return this.doc.querySelector('link[rel="icon"]');
  }

  /** Branche l'écoute du retour au premier plan une seule fois (rétablit le signal). */
  private ensureListener(): void {
    if (this.listenerBound) {
      return;
    }
    this.doc.addEventListener('visibilitychange', () => {
      if (!this.doc.hidden) {
        this.clear();
      }
    });
    this.listenerBound = true;
  }
}
