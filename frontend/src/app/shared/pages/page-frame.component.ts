import { Component, computed, inject, input } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

/**
 * Le seul bac à sable admis pour une page (cadrage F-109 §3.1) : scripts et fenêtres, **rien d'autre** —
 * jamais `allow-same-origin`, `allow-forms` ni `allow-top-navigation`. Exporté pour que les tests le lisent
 * au même endroit que le gabarit.
 */
export const PAGE_SANDBOX = 'allow-scripts allow-popups';

/** Préfixe des adresses de lecture : un ticket ou un lien de partage de la gateway, et rien d'autre. */
export const PAGE_URL_PREFIX = '/api/p/';

/** Vrai si l'adresse est une adresse de lecture de page de la gateway. */
export function isPageUrl(url: string | null | undefined): boolean {
  return !!url && url.startsWith(PAGE_URL_PREFIX) && !url.includes('..');
}

/**
 * **L'`iframe` d'une page** (F-109 / SF-109-03, décision D1) — l'unique fabrique : la vignette, le panneau,
 * le plein écran et la page partagée l'emploient, pour que la politique d'écran ne dérive nulle part.
 *
 * <p>La page est servie par la gateway avec `Content-Security-Policy: sandbox` (origine opaque, aucune sortie
 * réseau) ; l'`iframe` ajoute son propre bac à sable, identique. Une adresse qui n'est pas une adresse de
 * lecture de la gateway n'est **jamais** posée.</p>
 */
@Component({
  selector: 'app-page-frame',
  template: `
    @if (safeUrl(); as url) {
      <iframe
        class="page-frame"
        [class.page-frame--thumbnail]="thumbnail()"
        [src]="url"
        sandbox="allow-scripts allow-popups"
        referrerpolicy="no-referrer"
        [attr.loading]="thumbnail() ? 'lazy' : null"
        [attr.tabindex]="thumbnail() ? -1 : null"
        [attr.aria-hidden]="thumbnail() ? 'true' : null"
        [title]="label()"
      ></iframe>
    }
  `,
  styles: `
    :host {
      display: block;
      position: relative;
      overflow: hidden;
      background: var(--cg-surface);
    }

    .page-frame {
      display: block;
      width: 100%;
      height: 100%;
      border: 0;
    }

    /* Vignette : la page rendue à 1280 × 800, réduite au quart, inerte. */
    .page-frame--thumbnail {
      width: 1280px;
      height: 800px;
      transform: scale(0.25);
      transform-origin: 0 0;
      pointer-events: none;
    }
  `,
})
export class PageFrameComponent {
  private readonly sanitizer = inject(DomSanitizer);

  /** Adresse de lecture (`/api/p/…`). */
  readonly url = input<string | null>(null);
  /** Titre de la page, pour le `title` accessible de l'`iframe`. */
  readonly pageTitle = input<string>('');
  /** Vignette réduite et inerte, ou page pleine. */
  readonly thumbnail = input(false);

  readonly label = computed(() => (this.thumbnail() ? 'Aperçu de la page ' : 'Page ') + `« ${this.pageTitle()} »`);

  readonly safeUrl = computed<SafeResourceUrl | null>(() => {
    const url = this.url();
    // La confiance n'est accordée qu'aux adresses de lecture de la gateway : c'est le seul endroit du
    // produit qui contourne l'assainissement d'Angular pour une iframe, et il ne le fait que pour elles.
    return isPageUrl(url) ? this.sanitizer.bypassSecurityTrustResourceUrl(url as string) : null;
  });
}
