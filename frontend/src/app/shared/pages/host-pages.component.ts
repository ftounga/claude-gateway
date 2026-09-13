import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';
import { TextPromptDialogComponent, TextPromptDialogData } from '../../atelier/files/text-prompt-dialog.component';
import { PageSpace, PageSummary } from '../../core/models/pages.models';
import { ExportService } from '../../core/services/export.service';
import { PagesService } from '../../core/services/pages.service';
import { PageFrameComponent } from './page-frame.component';
import { PageVersionsDialogComponent, PageVersionsDialogData } from './page-versions-dialog.component';

/** Cartes par page de la grille. */
export const HOST_PAGES_PAGE_SIZE = 12;

/**
 * **Les pages d'un lieu** (F-109 / SF-109-04) : l'onglet Pages du poste (Forge) et du client (Vigie). Une carte
 * par page — vignette, titre, projet d'origine, date, version — et les gestes *Ouvrir*, *Renommer*, *Versions
 * précédentes*, *Télécharger*, *Supprimer*.
 */
@Component({
  selector: 'app-host-pages',
  imports: [DatePipe, MatButtonModule, MatIconModule, MatMenuModule, MatPaginatorModule, MatProgressSpinnerModule,
    PageFrameComponent],
  template: `
    @if (pages(); as list) {
      @if (list.length === 0) {
        <p class="host-pages__empty">
          Aucune page ici. Demandez-en une à l'agent du terminal : « fais-en une page ».
        </p>
      } @else {
        <div class="host-pages__grid">
          @for (page of visible(); track page.id) {
            <article class="host-pages__card" [attr.data-page]="page.id">
              <button type="button" class="host-pages__thumb" [attr.aria-label]="'Ouvrir la page ' + page.title" (click)="open(page)">
                <app-page-frame [url]="page.viewUrl" [pageTitle]="page.title" [thumbnail]="true"></app-page-frame>
              </button>
              <div class="host-pages__body">
                <h4 class="host-pages__title">{{ page.title }}</h4>
                @if (projectOf(page); as project) {
                  <p class="host-pages__project">{{ project }}</p>
                }
                <p class="host-pages__meta">Modifiée le {{ page.updatedAt | date: 'dd/MM/yyyy HH:mm' }} · v{{ page.currentVersion }}</p>
              </div>
              <button mat-icon-button type="button" class="host-pages__menu" [matMenuTriggerFor]="menu"
                [attr.aria-label]="'Actions sur la page ' + page.title">
                <mat-icon>more_vert</mat-icon>
              </button>
              <mat-menu #menu="matMenu">
                <button mat-menu-item type="button" class="host-pages__open" (click)="open(page)">
                  <mat-icon>open_in_new</mat-icon>Ouvrir
                </button>
                <button mat-menu-item type="button" class="host-pages__rename" (click)="rename(page)">
                  <mat-icon>edit</mat-icon>Renommer
                </button>
                <button mat-menu-item type="button" class="host-pages__versions" (click)="versions(page)">
                  <mat-icon>history</mat-icon>Versions précédentes
                </button>
                <button mat-menu-item type="button" class="host-pages__download" (click)="download(page)">
                  <mat-icon>download</mat-icon>Télécharger le fichier HTML
                </button>
                <button mat-menu-item type="button" class="host-pages__delete" (click)="remove(page)">
                  <mat-icon>delete</mat-icon>Supprimer
                </button>
              </mat-menu>
            </article>
          }
        </div>
        @if (list.length > pageSize) {
          <mat-paginator [length]="list.length" [pageSize]="pageSize" [pageIndex]="pageIndex()" [hidePageSize]="true"
            (page)="onPage($event)"></mat-paginator>
        }
      }
    } @else if (failed()) {
      <p class="host-pages__error" role="alert">Les pages n'ont pas pu être lues.</p>
    } @else {
      <div class="host-pages__loading"><mat-spinner diameter="28"></mat-spinner></div>
    }
  `,
  styles: `
    .host-pages__grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: var(--cg-space-3);
    }

    .host-pages__card {
      position: relative;
      display: flex;
      flex-direction: column;
      border-radius: 8px;
      background: var(--cg-surface);
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
      overflow: hidden;
    }

    .host-pages__thumb {
      display: block;
      width: 100%;
      height: 160px;
      padding: 0;
      border: 0;
      border-bottom: 1px solid var(--cg-divider);
      background: var(--cg-bg);
      cursor: pointer;
      overflow: hidden;

      app-page-frame {
        width: 320px;
        height: 200px;
      }
    }

    .host-pages__body {
      padding: var(--cg-space-2) var(--cg-space-6) var(--cg-space-3) var(--cg-space-3);
    }

    .host-pages__title {
      margin: 0;
      font-size: 16px;
      overflow-wrap: anywhere;
    }

    .host-pages__project,
    .host-pages__meta {
      margin: var(--cg-space-1) 0 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .host-pages__menu {
      position: absolute;
      right: var(--cg-space-1);
      bottom: var(--cg-space-1);
    }

    .host-pages__empty,
    .host-pages__error {
      color: var(--cg-text-secondary);
    }

    .host-pages__error {
      color: var(--cg-error);
    }
  `,
})
export class HostPagesComponent {
  private readonly service = inject(PagesService);
  private readonly files = inject(ExportService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly hostId = input.required<string>();
  readonly space = input.required<PageSpace>();
  /** Noms des projets du poste par identifiant : la carte dit d'où vient la page (Forge). */
  readonly projectNames = input<Record<string, string>>({});

  readonly pageSize = HOST_PAGES_PAGE_SIZE;
  readonly pages = signal<PageSummary[] | null>(null);
  readonly failed = signal(false);
  readonly pageIndex = signal(0);
  readonly visible = computed(() => {
    const start = this.pageIndex() * this.pageSize;
    return (this.pages() ?? []).slice(start, start + this.pageSize);
  });

  constructor() {
    effect(() => {
      const hostId = this.hostId();
      const space = this.space();
      untracked(() => this.load(hostId, space));
    });
  }

  projectOf(page: PageSummary): string | null {
    return page.workspaceId ? this.projectNames()[page.workspaceId] ?? null : null;
  }

  onPage(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
  }

  open(page: PageSummary): void {
    window.open(`/pages/${encodeURIComponent(page.id)}`, '_blank', 'noopener');
  }

  rename(page: PageSummary): void {
    this.dialog
      .open<TextPromptDialogComponent, TextPromptDialogData, string>(TextPromptDialogComponent, {
        data: { title: 'Renommer la page', label: 'Titre', confirmLabel: 'Renommer', initialValue: page.title,
          hint: 'Court et distinctif : deux à quatre mots.' },
        width: '480px', maxWidth: '95vw',
      })
      .afterClosed()
      .subscribe((title) => {
        if (!title || title === page.title) {
          return;
        }
        this.service.rename(page.id, title).subscribe({
          next: (updated) => {
            this.pages.update((list) => (list ?? []).map((item) => (item.id === updated.id ? updated : item)));
            this.snackBar.open('Page renommée.', 'Fermer', { duration: 4000, panelClass: 'snack-info' });
          },
          error: () => this.fail("La page n'a pas pu être renommée."),
        });
      });
  }

  versions(page: PageSummary): void {
    this.dialog.open<PageVersionsDialogComponent, PageVersionsDialogData>(PageVersionsDialogComponent, {
      data: { pageId: page.id, title: page.title }, width: '560px', maxWidth: '95vw',
    });
  }

  download(page: PageSummary): void {
    this.service.download(page.id).subscribe({
      next: (response) => this.files.triggerDownload(response, 'page.html'),
      error: () => this.fail("La page n'a pas pu être téléchargée."),
    });
  }

  remove(page: PageSummary): void {
    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
        data: {
          title: 'Supprimer la page ?',
          message: `« ${page.title} » et ses versions seront supprimées définitivement.`,
          confirmLabel: 'Supprimer',
        },
        width: '480px', maxWidth: '95vw',
      })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed !== true) {
          return;
        }
        this.service.remove(page.id).subscribe({
          next: () => {
            this.pages.update((list) => (list ?? []).filter((item) => item.id !== page.id));
            this.snackBar.open('Page supprimée.', 'Fermer', { duration: 4000, panelClass: 'snack-info' });
          },
          error: () => this.fail("La page n'a pas pu être supprimée. Rien n'a été effacé."),
        });
      });
  }

  private load(hostId: string, space: PageSpace): void {
    this.pages.set(null);
    this.failed.set(false);
    this.pageIndex.set(0);
    this.service.list(hostId, space).subscribe({
      next: (list) => this.pages.set(list),
      error: () => this.failed.set(true),
    });
  }

  private fail(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 8000, panelClass: 'snack-error' });
  }
}
