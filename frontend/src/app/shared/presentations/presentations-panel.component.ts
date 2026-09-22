import { DatePipe } from '@angular/common';
import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';
import { Presentation, PresentationSpace } from '../../core/models/presentation.models';
import { ExportService } from '../../core/services/export.service';
import { PresentationService } from '../../core/services/presentation.service';

/**
 * **Les présentations d'un lieu** (F-129 / SF-129-02) : l'onglet Présentations du poste (Forge) et du
 * client (Vigie). Une carte par présentation — titre, description, taille, date — et les gestes
 * *Télécharger* (le vrai .pptx) et *Supprimer*. La visionneuse (aperçu par slides) vient en SF-129-03.
 */
@Component({
  selector: 'app-presentations-panel',
  imports: [DatePipe, MatButtonModule, MatIconModule, MatMenuModule, MatProgressSpinnerModule],
  template: `
    @if (presentations(); as list) {
      @if (list.length === 0) {
        <p class="decks__empty">
          Aucune présentation ici. Demandez-en une à l'agent du terminal : « fais-en une présentation PowerPoint ».
        </p>
      } @else {
        <div class="decks__grid">
          @for (deck of list; track deck.id) {
            <article class="decks__card" [attr.data-deck]="deck.id">
              <div class="decks__icon" aria-hidden="true"><mat-icon>slideshow</mat-icon></div>
              <div class="decks__body">
                <h4 class="decks__title">{{ deck.title }}</h4>
                @if (deck.description) {
                  <p class="decks__desc">{{ deck.description }}</p>
                }
                <p class="decks__meta">
                  Modifiée le {{ deck.updatedAt | date: 'dd/MM/yyyy HH:mm' }} · {{ sizeLabel(deck.pptxBytes) }}
                  @if (deck.slideCount) { · {{ deck.slideCount }} slides }
                </p>
              </div>
              <button mat-icon-button type="button" class="decks__menu" [matMenuTriggerFor]="menu"
                [attr.aria-label]="'Actions sur la présentation ' + deck.title">
                <mat-icon>more_vert</mat-icon>
              </button>
              <mat-menu #menu="matMenu">
                <button mat-menu-item type="button" class="decks__download" (click)="download(deck)">
                  <mat-icon>download</mat-icon>Télécharger le .pptx
                </button>
                <button mat-menu-item type="button" class="decks__delete" (click)="remove(deck)">
                  <mat-icon>delete</mat-icon>Supprimer
                </button>
              </mat-menu>
            </article>
          }
        </div>
      }
    } @else if (failed()) {
      <p class="decks__error" role="alert">Les présentations n'ont pas pu être lues.</p>
    } @else {
      <div class="decks__loading"><mat-spinner diameter="28"></mat-spinner></div>
    }
  `,
  styles: `
    .decks__grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
      gap: var(--cg-space-3);
    }

    .decks__card {
      position: relative;
      display: flex;
      align-items: flex-start;
      gap: var(--cg-space-3);
      padding: var(--cg-space-3) var(--cg-space-6) var(--cg-space-3) var(--cg-space-3);
      border-radius: 8px;
      background: var(--cg-surface);
      border: 1px solid var(--cg-divider);
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
    }

    .decks__icon {
      display: grid;
      place-items: center;
      flex: none;
      width: 44px;
      height: 44px;
      border-radius: 8px;
      background: var(--cg-primary);
      color: #fff;
    }

    .decks__body {
      min-width: 0;
    }

    .decks__title {
      margin: 0;
      font-size: 16px;
      overflow-wrap: anywhere;
    }

    .decks__desc,
    .decks__meta {
      margin: var(--cg-space-1) 0 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .decks__menu {
      position: absolute;
      right: var(--cg-space-1);
      top: var(--cg-space-1);
    }

    .decks__empty,
    .decks__error {
      color: var(--cg-text-secondary);
    }

    .decks__error {
      color: var(--cg-error);
    }
  `,
})
export class PresentationsPanelComponent {
  private readonly service = inject(PresentationService);
  private readonly files = inject(ExportService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly hostId = input.required<string>();
  readonly space = input.required<PresentationSpace>();

  readonly presentations = signal<Presentation[] | null>(null);
  readonly failed = signal(false);

  constructor() {
    effect(() => {
      const hostId = this.hostId();
      const space = this.space();
      untracked(() => this.load(hostId, space));
    });
  }

  /** Une taille lisible : « 1,2 Mo », « 340 Ko ». */
  sizeLabel(bytes: number): string {
    if (bytes >= 1024 * 1024) {
      return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} Mo`;
    }
    return `${Math.max(1, Math.round(bytes / 1024))} Ko`;
  }

  download(deck: Presentation): void {
    this.service.download(deck.id).subscribe({
      next: (response) => this.files.triggerDownload(response, 'presentation.pptx'),
      error: () => this.fail("La présentation n'a pas pu être téléchargée."),
    });
  }

  remove(deck: Presentation): void {
    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
        data: {
          title: 'Supprimer la présentation ?',
          message: `« ${deck.title} » et son fichier seront supprimés définitivement.`,
          confirmLabel: 'Supprimer',
        },
        width: '480px', maxWidth: '95vw',
      })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed !== true) {
          return;
        }
        this.service.delete(deck.id).subscribe({
          next: () => {
            this.presentations.update((list) => (list ?? []).filter((item) => item.id !== deck.id));
            this.snackBar.open('Présentation supprimée.', 'Fermer', { duration: 4000, panelClass: 'snack-info' });
          },
          error: () => this.fail("La présentation n'a pas pu être supprimée. Rien n'a été effacé."),
        });
      });
  }

  private load(hostId: string, space: PresentationSpace): void {
    this.presentations.set(null);
    this.failed.set(false);
    this.service.list(hostId, space).subscribe({
      next: (list) => this.presentations.set(list),
      error: () => this.failed.set(true),
    });
  }

  private fail(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 8000, panelClass: 'snack-error' });
  }
}
