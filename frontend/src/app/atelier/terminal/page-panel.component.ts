import { Component, DestroyRef, HostListener, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog } from '@angular/material/dialog';
import { Subscription } from 'rxjs';

import { PageSummary } from '../../core/models/pages.models';
import { PagesService } from '../../core/services/pages.service';
import { PageFrameComponent } from '../../shared/pages/page-frame.component';
import { PageShareDialogComponent, PageShareDialogData } from '../../shared/pages/page-share-dialog.component';
import { pageViewerPath } from './page-block';

/**
 * **Le panneau d'une page, à droite du terminal** (F-109 / SF-109-03) : l'`iframe` pleine hauteur, *Plein
 * écran* et *Fermer*. Échap le ferme. Le ticket de lecture est redemandé à chaque ouverture — il expire.
 */
@Component({
  selector: 'app-page-panel',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, PageFrameComponent],
  template: `
    <aside class="page-panel" role="complementary" [attr.aria-label]="'Page ' + (summary()?.title ?? '')">
      <header class="page-panel__bar">
        <h2 class="page-panel__title">{{ summary()?.title ?? 'Page' }}</h2>
        @if (summary(); as page) {
          <span class="page-panel__version">v{{ page.currentVersion }}</span>
        }
        <span class="page-panel__spacer"></span>
        @if (summary(); as page) {
          <button mat-icon-button type="button" class="page-panel__share" matTooltip="Partager"
            aria-label="Partager la page" (click)="share(page.id, page.title)">
            <mat-icon>share</mat-icon>
          </button>
        }
        <button mat-icon-button type="button" class="page-panel__fullscreen" matTooltip="Plein écran"
          aria-label="Ouvrir la page en plein écran" (click)="fullscreen()">
          <mat-icon>open_in_new</mat-icon>
        </button>
        <button mat-icon-button type="button" class="page-panel__close" matTooltip="Fermer (Échap)"
          aria-label="Fermer la page" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>
      <div class="page-panel__content">
        @if (summary(); as page) {
          <app-page-frame class="page-panel__frame" [url]="page.viewUrl" [pageTitle]="page.title"></app-page-frame>
        } @else if (unavailable()) {
          <p class="page-panel__unavailable">Aperçu indisponible : la page a peut-être été supprimée.</p>
        }
      </div>
    </aside>
  `,
  styles: `
    :host {
      position: fixed;
      top: 64px;
      right: 0;
      bottom: 0;
      z-index: 900;
      width: min(640px, 50vw);
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
    }

    @media (max-width: 899px) {
      :host {
        width: 100vw;
      }
    }

    .page-panel {
      display: flex;
      flex-direction: column;
      height: 100%;
      background: var(--cg-surface);
      border-left: 1px solid var(--cg-divider);
    }

    .page-panel__bar {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-2) var(--cg-space-1) var(--cg-space-3);
      border-bottom: 1px solid var(--cg-divider);
    }

    .page-panel__title {
      margin: 0;
      font-size: 18px;
      font-weight: 600;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .page-panel__version {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .page-panel__spacer {
      flex: 1;
    }

    .page-panel__content {
      flex: 1;
      min-height: 0;
    }

    .page-panel__frame {
      height: 100%;
    }

    .page-panel__unavailable {
      margin: var(--cg-space-4);
      color: var(--cg-text-secondary);
    }
  `,
})
export class PagePanelComponent {
  private readonly pages = inject(PagesService);
  private readonly dialog = inject(MatDialog);

  readonly pageId = input.required<string>();
  readonly closed = output<void>();

  readonly summary = signal<PageSummary | null>(null);
  readonly unavailable = signal(false);

  private loading: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.loading?.unsubscribe());
    effect(() => {
      const pageId = this.pageId();
      untracked(() => this.load(pageId));
    });
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  /** Partager (F-109 / SF-109-05). */
  share(pageId: string, title: string): void {
    this.dialog.open<PageShareDialogComponent, PageShareDialogData>(PageShareDialogComponent, {
      data: { pageId, title }, width: '640px', maxWidth: '95vw',
    });
  }

  fullscreen(): void {
    window.open(pageViewerPath(this.pageId()), '_blank', 'noopener');
  }

  private load(pageId: string): void {
    this.loading?.unsubscribe();
    this.summary.set(null);
    this.unavailable.set(false);
    this.loading = this.pages.get(pageId).subscribe({
      next: (summary) => this.summary.set(summary),
      error: () => this.unavailable.set(true),
    });
  }
}
