import { NgTemplateOutlet } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

import { httpErrorMessage, humanFileSize } from '../../shared/http-error.util';
import {
  MAX_TABLE_ROWS, PreviewKind, PreviewSheet, cellText, depositContentUrl, depositFileName, parseCsv, previewKindOf,
} from './deposit-preview';

/** Ce que l'aperçu reçoit : le terminal et la pièce (identifiant de dépôt, chemin, taille). */
export interface DepositPreviewData {
  workspaceId: string;
  depositId: string;
  path: string;
  size: number;
}

/**
 * **L'aperçu d'une pièce jointe** (F-228 / SF-228-02) — au clic sur la puce, jamais au survol (une lecture sur le
 * poste, et rien au toucher). Image : zoom, [Copier], [Télécharger] (même geste que les captures F-212) ; PDF : le
 * lecteur du navigateur ; vidéo et audio : les lecteurs natifs ; SVG : en image seulement (aucun script) ; le reste :
 * [Télécharger]. Jamais de HTML brut. Charte : `MatDialog`, encres des écrans clairs.
 */
@Component({
  selector: 'app-deposit-preview-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule, NgTemplateOutlet],
  template: `
    <h2 mat-dialog-title class="dp__title">{{ name }} <span class="dp__size">{{ size }}</span></h2>
    <mat-dialog-content class="dp">
      @if (state() === 'loading') {
        <div class="dp__loading"><mat-spinner diameter="32"></mat-spinner></div>
      } @else if (state() === 'error') {
        <p class="dp__error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon>{{ error() }}</p>
      } @else {
        @switch (kind) {
          @case ('image') {
            <button type="button" class="dp__zoom" [class.dp__zoom--full]="zoomed()" (click)="zoomed.set(!zoomed())"
              [attr.aria-pressed]="zoomed()" [attr.aria-label]="zoomed() ? 'Ajuster à la fenêtre' : 'Taille réelle'">
              <img class="dp__image" [src]="safeUrl()" [alt]="name" />
            </button>
          }
          @case ('svg') {
            <img class="dp__image" [src]="safeUrl()" [alt]="name" />
          }
          @case ('pdf') {
            <object class="dp__pdf" type="application/pdf" [attr.data]="safeUrl()" [attr.aria-label]="name">
              <p>Le lecteur PDF du navigateur n'est pas disponible : <button mat-button type="button" (click)="download()">Télécharger</button></p>
            </object>
          }
          @case ('video') {
            <video class="dp__media" controls [src]="safeUrl()"></video>
          }
          @case ('audio') {
            <audio class="dp__audio" controls [src]="safeUrl()"></audio>
          }
          @case ('csv') {
            <ng-container [ngTemplateOutlet]="table"></ng-container>
          }
          @case ('xlsx') {
            <ng-container [ngTemplateOutlet]="table"></ng-container>
          }
          @default {
            <p class="dp__note">Pas d'aperçu pour ce type de fichier : téléchargez-le.</p>
          }
        }
      }
    </mat-dialog-content>
    <!-- F-228 / SF-228-03 : un tableur en lecture seule — premier onglet, sélecteur, 500 lignes au plus. -->
    <ng-template #table>
      @if (tableError(); as message) {
        <p class="dp__error" role="alert">{{ message }}</p>
      } @else if (sheets().length === 0) {
        <div class="dp__loading"><mat-spinner diameter="32"></mat-spinner></div>
      } @else {
        @if (sheets().length > 1) {
          <div class="dp__sheets" role="tablist" aria-label="Onglets du classeur">
            @for (s of sheets(); track $index; let i = $index) {
              <button type="button" role="tab" class="dp__sheet" [class.dp__sheet--on]="i === sheetIndex()"
                [attr.aria-selected]="i === sheetIndex()" (click)="sheetIndex.set(i)">{{ s.name }}</button>
            }
          </div>
        }
        @if (currentSheet(); as s) {
          <div class="dp__table-wrap">
            <table class="dp__table">
              @for (row of s.rows; track $index; let r = $index) {
                <tr>
                  @for (cell of row; track $index) {
                    @if (r === 0) { <th scope="col">{{ cell }}</th> } @else { <td>{{ cell }}</td> }
                  }
                </tr>
              }
            </table>
          </div>
          @if (s.truncated) {
            <p class="dp__note">Les {{ maxRows }} premières lignes seulement : téléchargez le fichier pour le reste.</p>
          }
        }
      }
    </ng-template>
    <mat-dialog-actions align="end">
      @if (state() === 'ready' && kind === 'image') {
        <button mat-button type="button" class="dp__copy" (click)="copy()"><mat-icon>content_copy</mat-icon>Copier</button>
      }
      @if (state() === 'ready') {
        <button mat-button type="button" class="dp__download" (click)="download()"><mat-icon>download</mat-icon>Télécharger</button>
      }
      <button mat-flat-button type="button" mat-dialog-close>Fermer</button>
    </mat-dialog-actions>
  `,
  styles: `
    .dp__title { display: flex; align-items: baseline; gap: var(--cg-space-2); overflow-wrap: anywhere; }
    .dp__size { color: var(--cg-text-secondary); font-size: 13px; font-weight: 400; }
    .dp { min-width: min(560px, 80vw); max-width: 92vw; }
    .dp__loading { display: flex; justify-content: center; padding: var(--cg-space-5); }
    .dp__error { display: flex; gap: var(--cg-space-2); align-items: flex-start; color: var(--cg-error); }
    .dp__zoom { display: block; width: 100%; padding: 0; border: 0; background: none; cursor: zoom-in; overflow: auto; }
    .dp__zoom--full { cursor: zoom-out; }
    .dp__image { display: block; max-width: 100%; max-height: 70vh; margin: 0 auto; }
    .dp__zoom--full .dp__image { max-width: none; max-height: none; }
    .dp__pdf { width: 100%; height: 70vh; }
    .dp__media { display: block; width: 100%; max-height: 70vh; }
    .dp__audio { width: 100%; }
    .dp__note { color: var(--cg-text-secondary); }
    .dp__sheets { display: flex; flex-wrap: wrap; gap: var(--cg-space-1); margin-bottom: var(--cg-space-2); }
    .dp__sheet { padding: var(--cg-space-1) var(--cg-space-2); border: 1px solid var(--cg-divider); border-radius: 4px;
      background: var(--cg-surface); color: var(--cg-text-primary); font: inherit; cursor: pointer; }
    .dp__sheet--on { border-color: var(--cg-primary); font-weight: 600; }
    .dp__table-wrap { max-height: 60vh; overflow: auto; border: 1px solid var(--cg-divider); }
    .dp__table { border-collapse: collapse; font-size: 13px; }
    .dp__table th, .dp__table td { padding: 2px var(--cg-space-2); border: 1px solid var(--cg-divider);
      text-align: left; white-space: nowrap; }
    .dp__table th { position: sticky; top: 0; background: var(--cg-surface-2); }
    @media (max-width: 599px) { .dp { min-width: 0; } .dp__pdf { height: 60vh; } }
  `,
})
export class DepositPreviewDialogComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly snackBar = inject(MatSnackBar);
  readonly data = inject<DepositPreviewData>(MAT_DIALOG_DATA);

  readonly kind: PreviewKind = previewKindOf(this.data.path);
  readonly name = depositFileName(this.data.path);
  readonly size = humanFileSize(this.data.size ?? 0);

  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly error = signal<string>('');
  readonly zoomed = signal(false);
  readonly objectUrl = signal<string | null>(null);
  readonly safeUrl = signal<SafeResourceUrl | null>(null);

  /** Les octets reçus (pour Copier, Télécharger, et les tableurs). */
  blob: Blob | null = null;

  /** Les onglets d'un tableur lu (F-228 / SF-228-03), le premier montré d'abord. */
  readonly sheets = signal<PreviewSheet[]>([]);
  readonly sheetIndex = signal(0);
  readonly currentSheet = computed(() => this.sheets()[this.sheetIndex()] ?? null);
  readonly tableError = signal<string | null>(null);
  readonly maxRows = MAX_TABLE_ROWS;

  /** Lit le tableur : CSV par l'analyseur maison ; xlsx par une bibliothèque chargée À CE CLIC seulement. */
  private async readTable(blob: Blob): Promise<void> {
    try {
      if (this.kind === 'csv') {
        this.sheets.set([parseCsv(await blob.text())]);
        return;
      }
      const { default: readXlsxFile } = await import('read-excel-file/browser');
      const sheets = await readXlsxFile(blob);
      this.sheets.set(sheets.map((s) => ({
        name: s.sheet,
        rows: s.data.slice(0, MAX_TABLE_ROWS).map((row) => row.map((cell) => cellText(cell))),
        truncated: s.data.length > MAX_TABLE_ROWS,
      })));
    } catch {
      this.tableError.set('Ce tableur n’a pas pu être lu : téléchargez-le.');
    }
  }

  ngOnInit(): void {
    this.http.get(depositContentUrl(this.data.workspaceId, this.data.depositId), { responseType: 'blob' }).subscribe({
      next: (blob) => {
        this.blob = blob;
        const url = URL.createObjectURL(blob);
        this.objectUrl.set(url);
        // Une URL blob: de NOS octets, servis `nosniff` + CSP sandbox : jamais du HTML (passerelle).
        this.safeUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(url));
        this.state.set('ready');
        if (this.kind === 'csv' || this.kind === 'xlsx') {
          void this.readTable(blob);
        }
      },
      error: (err: HttpErrorResponse) => {
        this.error.set(err.status === 404 ? 'Pièce introuvable.' : httpErrorMessage(err, 'La pièce n’a pas pu être lue.'));
        this.state.set('error');
      },
    });
  }

  ngOnDestroy(): void {
    const url = this.objectUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
  }

  /** [Copier] une image : presse-papiers (PNG) ; repli [Télécharger] si le navigateur refuse. */
  copy(): void {
    const clipboard = navigator.clipboard;
    if (!this.blob || !clipboard || typeof clipboard.write !== 'function' || typeof ClipboardItem === 'undefined') {
      this.snackBar.open('Copie d’image impossible ici : l’image est téléchargée.', 'Fermer', { duration: 3000 });
      this.download();
      return;
    }
    const type = this.blob.type && this.blob.type.startsWith('image/') ? this.blob.type : 'image/png';
    clipboard.write([new ClipboardItem({ [type]: this.blob })]).then(
      () => this.snackBar.open('Image copiée.', 'Fermer', { duration: 2500 }),
      () => {
        this.snackBar.open('Copie d’image refusée par le navigateur : l’image est téléchargée.', 'Fermer', { duration: 3000 });
        this.download();
      },
    );
  }

  /** [Télécharger] la pièce, sous son nom. */
  download(): void {
    const url = this.objectUrl();
    if (!url) {
      return;
    }
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = this.name;
    anchor.click();
  }
}
