import { Clipboard } from '@angular/cdk/clipboard';
import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { PageJournalEntry, PageShareSummary } from '../../core/models/pages.models';
import { PagesService } from '../../core/services/pages.service';

export interface PageShareDialogData {
  pageId: string;
  title: string;
}

/** L'avertissement du cadrage (§6), mot pour mot. */
export const SHARE_WARNING =
  "Cette page contient peut-être des données de votre client ; vérifiez qu'il autorise leur diffusion.";

/** Ce que dit une ligne du journal. */
export function journalLabel(entry: PageJournalEntry): string {
  switch (entry.kind) {
    case 'CREATED':
      return 'Page créée';
    case 'VERSION':
      return `Version ${entry.version ?? ''} publiée`.replace('  ', ' ');
    case 'SHARED':
      return 'Lien de partage créé';
    case 'OPENED':
      return 'Lien de partage ouvert';
    case 'REVOKED':
      return 'Lien de partage révoqué';
    default:
      return entry.kind;
  }
}

/** L'état d'un lien en toutes lettres. */
export function shareStateLabel(share: PageShareSummary): string {
  switch (share.state) {
    case 'ACTIVE':
      return 'actif';
    case 'EXPIRED':
      return 'expiré';
    case 'REVOKED':
      return 'révoqué';
    default:
      return share.state;
  }
}

/**
 * **Partager une page** (F-109 / SF-109-05) : l'avertissement d'abord, puis un lien à durée choisie (1 à 90
 * jours), copiable une fois ; les liens existants avec leurs ouvertures, révocables ; le journal de la page.
 */
@Component({
  selector: 'app-page-share-dialog',
  imports: [DatePipe, FormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule,
    MatIconModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Partager « {{ data.title }} »</h2>
    <mat-dialog-content>
      <p class="page-share__warning" role="note">
        <mat-icon aria-hidden="true">info</mat-icon>
        <span>{{ warning }}</span>
      </p>
      <mat-checkbox class="page-share__checked" [(ngModel)]="checked">J'ai vérifié : je peux diffuser cette page.</mat-checkbox>

      <div class="page-share__create">
        <mat-form-field appearance="outline" class="page-share__days">
          <mat-label>Durée du lien (jours)</mat-label>
          <input matInput type="number" min="1" max="90" name="days" [(ngModel)]="days" />
          @if (!daysValid()) {
            <mat-error>De 1 à 90 jours.</mat-error>
          }
          <mat-hint>7 jours par défaut, 90 au plus</mat-hint>
        </mat-form-field>
        <button mat-flat-button color="primary" type="button" class="page-share__submit"
          [disabled]="!checked || !daysValid() || creating()" (click)="create()">
          <mat-icon>link</mat-icon>
          Créer le lien
        </button>
      </div>

      @if (createdUrl(); as url) {
        <div class="page-share__created" role="status">
          <p>Lien créé. Copiez-le maintenant : il ne sera plus affiché.</p>
          <div class="page-share__url-row">
            <code class="page-share__url">{{ url }}</code>
            <button mat-stroked-button type="button" class="page-share__copy" (click)="copy(url)">
              <mat-icon>content_copy</mat-icon>
              Copier
            </button>
          </div>
        </div>
      }

      <h3 class="page-share__heading">Liens</h3>
      @if (shares().length === 0) {
        <p class="page-share__empty">Aucun lien pour cette page.</p>
      } @else {
        <ul class="page-share__list">
          @for (share of shares(); track share.id) {
            <li class="page-share__item" [attr.data-state]="share.state">
              <span>
                <strong>{{ stateLabel(share) }}</strong>
                · créé le {{ share.createdAt | date: 'dd/MM/yyyy HH:mm' }}
                · expire le {{ share.expiresAt | date: 'dd/MM/yyyy' }}
                · {{ share.openCount }} ouverture{{ share.openCount > 1 ? 's' : '' }}
                @if (share.lastOpenedAt) {
                  (dernière le {{ share.lastOpenedAt | date: 'dd/MM/yyyy HH:mm' }})
                }
              </span>
              @if (share.state === 'ACTIVE') {
                <button mat-stroked-button type="button" class="page-share__revoke" (click)="revoke(share)">Révoquer</button>
              }
            </li>
          }
        </ul>
      }

      <h3 class="page-share__heading">Journal</h3>
      @if (journal().length === 0) {
        <p class="page-share__empty">Rien encore.</p>
      } @else {
        <ul class="page-share__journal">
          @for (entry of journal(); track $index) {
            <li><span class="page-share__when">{{ entry.occurredAt | date: 'dd/MM/yyyy HH:mm' }}</span> {{ label(entry) }}</li>
          }
        </ul>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-stroked-button mat-dialog-close type="button">Fermer</button>
    </mat-dialog-actions>
  `,
  styles: `
    .page-share__warning {
      display: flex;
      align-items: flex-start;
      gap: var(--cg-space-2);
      margin: 0 0 var(--cg-space-2);
      padding: var(--cg-space-3);
      border-radius: 8px;
      background: var(--cg-bg);

      mat-icon {
        flex: none;
        color: var(--cg-text-secondary);
      }
    }

    .page-share__create {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--cg-space-3);
      margin-top: var(--cg-space-3);
    }

    .page-share__days {
      width: 200px;
    }

    .page-share__submit {
      margin-top: var(--cg-space-2);
    }

    .page-share__created {
      margin-top: var(--cg-space-2);
      padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;

      p {
        margin: 0 0 var(--cg-space-2);
      }
    }

    .page-share__url-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .page-share__url {
      overflow-wrap: anywhere;
      font-size: 12px;
    }

    .page-share__heading {
      margin: var(--cg-space-4) 0 var(--cg-space-2);
      font-size: 16px;
    }

    .page-share__list,
    .page-share__journal {
      margin: 0;
      padding: 0;
      list-style: none;
      font-size: 14px;
    }

    .page-share__item {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--cg-space-2);
      padding: var(--cg-space-2) 0;
      border-bottom: 1px solid var(--cg-divider);
    }

    .page-share__item[data-state='EXPIRED'],
    .page-share__item[data-state='REVOKED'] {
      color: var(--cg-text-secondary);
    }

    .page-share__when,
    .page-share__empty {
      color: var(--cg-text-secondary);
    }
  `,
})
export class PageShareDialogComponent {
  readonly data = inject<PageShareDialogData>(MAT_DIALOG_DATA);
  private readonly pages = inject(PagesService);
  private readonly clipboard = inject(Clipboard);
  private readonly snackBar = inject(MatSnackBar);

  readonly warning = SHARE_WARNING;
  checked = false;
  days = 7;

  readonly creating = signal(false);
  readonly createdUrl = signal<string | null>(null);
  readonly shares = signal<PageShareSummary[]>([]);
  readonly journal = signal<PageJournalEntry[]>([]);

  constructor() {
    this.refresh();
  }

  daysValid(): boolean {
    return Number.isInteger(this.days) && this.days >= 1 && this.days <= 90;
  }

  label(entry: PageJournalEntry): string {
    return journalLabel(entry);
  }

  stateLabel(share: PageShareSummary): string {
    return shareStateLabel(share);
  }

  create(): void {
    if (!this.checked || !this.daysValid()) {
      return;
    }
    this.creating.set(true);
    this.pages.createShare(this.data.pageId, this.days).subscribe({
      next: (created) => {
        this.creating.set(false);
        this.createdUrl.set(`${window.location.origin}${created.url}`);
        this.refresh();
      },
      error: () => {
        this.creating.set(false);
        this.fail("Le lien n'a pas pu être créé.");
      },
    });
  }

  copy(url: string): void {
    this.clipboard.copy(url);
    this.snackBar.open('Lien copié.', 'Fermer', { duration: 3000, panelClass: 'snack-info' });
  }

  revoke(share: PageShareSummary): void {
    this.pages.revokeShare(this.data.pageId, share.id).subscribe({
      next: () => {
        this.snackBar.open('Lien révoqué : il ne s\'ouvre plus.', 'Fermer', { duration: 4000, panelClass: 'snack-info' });
        this.refresh();
      },
      error: () => this.fail("Le lien n'a pas pu être révoqué."),
    });
  }

  private refresh(): void {
    this.pages.shares(this.data.pageId).subscribe({ next: (list) => this.shares.set(list), error: () => undefined });
    this.pages.journal(this.data.pageId).subscribe({ next: (list) => this.journal.set(list), error: () => undefined });
  }

  private fail(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 8000, panelClass: 'snack-error' });
  }
}
