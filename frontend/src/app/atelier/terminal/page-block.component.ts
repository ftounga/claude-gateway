import { Component, DestroyRef, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Subscription } from 'rxjs';

import { AtelierTerminalPage } from '../../core/models/atelier.models';
import { PagesService } from '../../core/services/pages.service';
import { PageFrameComponent } from '../../shared/pages/page-frame.component';
import { pageHeadline, pageViewerPath } from './page-block';

/**
 * **Le bloc « Page publiée »** d'un terminal (F-109 / SF-109-03) : le titre, la vignette, *Ouvrir* et *Plein
 * écran*. Admis dans **tout** terminal — les sorties de commande restent textuelles, une page est un document
 * rendu par l'agent (amendement F-89, charte §18).
 *
 * <p>En lecture seule (tuile de « Voir travailler »), les boutons disparaissent : on n'agit pas depuis une
 * tuile (charte §13). La vignette reste.</p>
 */
@Component({
  selector: 'app-page-block',
  imports: [MatButtonModule, MatIconModule, PageFrameComponent],
  template: `
    <article class="page-block" [attr.aria-label]="headline()">
      <div class="page-block__thumb">
        @if (viewUrl(); as url) {
          <app-page-frame [url]="url" [pageTitle]="page().title" [thumbnail]="true"></app-page-frame>
        } @else if (unavailable()) {
          <p class="page-block__unavailable">Aperçu indisponible</p>
        }
      </div>
      <div class="page-block__body">
        <p class="page-block__title">
          <mat-icon class="page-block__icon" aria-hidden="true">web</mat-icon>
          <span>{{ headline() }}</span>
        </p>
        <p class="page-block__meta">Version {{ page().version }} · privée</p>
        @if (page().description; as description) {
          <p class="page-block__description">{{ description }}</p>
        }
        @if (!readOnly()) {
          <div class="page-block__actions">
            <button mat-flat-button color="primary" type="button" class="page-block__open" (click)="open.emit(page().pageId)">
              <mat-icon>vertical_split</mat-icon>
              Ouvrir
            </button>
            <button mat-stroked-button type="button" class="page-block__fullscreen" (click)="fullscreen()">
              <mat-icon>open_in_new</mat-icon>
              Plein écran
            </button>
          </div>
        }
      </div>
    </article>
  `,
  styles: `
    .page-block {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-3);
      margin: var(--cg-space-2) 0;
      padding: var(--cg-space-3);
      border-radius: 8px;
      background: var(--cg-surface);
      color: var(--cg-text-primary);
      font-family: var(--cg-font-body);
      /* ÎLOT BLANC dans un terminal sombre : le terminal redéfinit les inks Material sur une encre
         claire (F-30 / SF-30-15), qui se propage ici par héritage de variables CSS et rendrait
         « Plein écran » blanc-sur-blanc. On rétablit l'encre foncée sur notre propre surface. */
      --mdc-outlined-button-label-text-color: var(--cg-primary);
      --mdc-outlined-button-outline-color: var(--cg-divider);
      --mdc-text-button-label-text-color: var(--cg-primary);
      --mdc-icon-button-icon-color: var(--cg-text-secondary);
    }

    .page-block__thumb {
      flex: none;
      width: 320px;
      max-width: 100%;
      height: 200px;
      border: 1px solid var(--cg-divider);
      border-radius: 4px;
      overflow: hidden;
      background: var(--cg-bg);
    }

    .page-block__thumb app-page-frame {
      width: 100%;
      height: 100%;
    }

    .page-block__unavailable {
      display: grid;
      place-items: center;
      height: 100%;
      margin: 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .page-block__body {
      flex: 1 1 220px;
      min-width: 0;
    }

    .page-block__title {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      margin: 0;
      font-family: var(--cg-font-heading);
      font-size: 18px;
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .page-block__icon {
      flex: none;
      color: var(--cg-text-secondary);
    }

    .page-block__meta,
    .page-block__description {
      margin: var(--cg-space-1) 0 0;
      font-size: 14px;
    }

    .page-block__meta {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .page-block__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-3);
    }
  `,
})
export class PageBlockComponent {
  private readonly pages = inject(PagesService);

  readonly page = input.required<AtelierTerminalPage>();
  readonly readOnly = input(false);
  /** *Ouvrir* : l'identifiant de la page à montrer dans le panneau. */
  readonly open = output<string>();

  readonly viewUrl = signal<string | null>(null);
  readonly unavailable = signal(false);

  private loading: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.loading?.unsubscribe());
    effect(() => {
      const pageId = this.page().pageId;
      untracked(() => this.load(pageId));
    });
  }

  headline(): string {
    return pageHeadline(this.page());
  }

  /** *Plein écran* : un nouvel onglet — le tour vit dans le flux du terminal, le quitter le tuerait. */
  fullscreen(): void {
    window.open(pageViewerPath(this.page().pageId), '_blank', 'noopener');
  }

  private load(pageId: string): void {
    this.loading?.unsubscribe();
    this.viewUrl.set(null);
    this.unavailable.set(false);
    this.loading = this.pages.get(pageId).subscribe({
      next: (summary) => this.viewUrl.set(summary.viewUrl),
      // Page supprimée depuis, ou illisible : le bloc reste, et le dit.
      error: () => this.unavailable.set(true),
    });
  }
}
