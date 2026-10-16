import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AppRequestAttachmentView, AppRequestStatus, AppRequestView } from '../../core/models/atelier.models';
import {
  AppRequestService,
  appRequestKindLabel,
  appRequestStatusLabel,
  sizeLabel,
} from '../../core/services/app-request.service';

/** Filtres proposés : un statut, ou tous les besoins enregistrés (`null`). */
export const BESOIN_FILTERS: { value: AppRequestStatus | null; label: string }[] = [
  { value: 'EN_ATTENTE', label: 'En attente' },
  { value: 'CADRE', label: 'Cadrés' },
  { value: 'LIVRE', label: 'Livrés' },
  { value: 'REJETE', label: 'Rejetés' },
  { value: null, label: 'Tous' },
];

/** Statuts qu'un administrateur peut poser. */
export const BESOIN_STATUSES: AppRequestStatus[] = ['EN_ATTENTE', 'CADRE', 'REJETE', 'LIVRE'];

const FEATURE_REF = /^F-\d{1,4}$/i;

/**
 * Section **Besoins** de l'administration (F-205 / SF-205-02) : les bugs et évolutions de
 * l'application signalés depuis un terminal et enregistrés d'un clic. On les filtre, on relit les mots
 * de l'utilisateur et ses pièces, on pose le statut et la feature qui les porte. Claude Code lit la même
 * chose par `GET /api/admin/app-requests?status=EN_ATTENTE`.
 */
@Component({
  selector: 'app-admin-besoins',
  imports: [DatePipe, FormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatIconModule,
    MatInputModule, MatSelectModule],
  template: `
    <mat-card class="besoins">
      <mat-card-header>
        <mat-card-title>Besoins</mat-card-title>
        <mat-card-subtitle>
          Bugs et évolutions de l’application signalés depuis un terminal. Claude Code les relit par
          <code>/api/admin/app-requests</code>.
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <mat-form-field appearance="outline" class="besoins__filter">
          <mat-label>Statut</mat-label>
          <mat-select [(ngModel)]="filter" name="filter" (selectionChange)="load()">
            @for (f of filters; track f.label) {
              <mat-option [value]="f.value">{{ f.label }}</mat-option>
            }
          </mat-select>
        </mat-form-field>

        @if (loading()) {
          <p class="besoins__note">Chargement…</p>
        } @else if (!items().length) {
          <p class="besoins__note">Aucun besoin pour ce filtre.</p>
        }

        @for (item of items(); track item.id) {
          <article class="besoin" [class.besoin--open]="openId() === item.id">
            <button type="button" class="besoin__head" [attr.aria-expanded]="openId() === item.id" (click)="toggle(item)">
              <mat-icon aria-hidden="true">{{ item.kind === 'BUG' ? 'bug_report' : 'lightbulb' }}</mat-icon>
              <span class="besoin__kind">{{ kind(item.kind) }}</span>
              <span class="besoin__title">{{ item.title }}</span>
              <span class="besoin__meta">
                {{ status(item.status) }}@if (item.featureRef) { · {{ item.featureRef }} } · {{ item.createdAt | date: 'short' }}
              </span>
            </button>

            @if (openId() === item.id) {
              <div class="besoin__detail">
                <h3 class="besoin__subtitle">Ses mots</h3>
                <blockquote class="besoin__words">{{ item.description }}</blockquote>
                @if (item.reformulation) {
                  <h3 class="besoin__subtitle">Reformulation</h3>
                  <p class="besoin__text">{{ item.reformulation }}</p>
                }
                @if (item.observedContext) {
                  <h3 class="besoin__subtitle">Observé</h3>
                  <p class="besoin__text">{{ item.observedContext }}</p>
                }

                @if (item.attachments.length) {
                  <h3 class="besoin__subtitle">Pièces</h3>
                  <ul class="besoin__pieces">
                    @for (piece of item.attachments; track piece.position) {
                      <li class="piece">
                        <span class="piece__name">{{ piece.name }} <span class="besoins__note">{{ size(piece.size) }}</span></span>
                        @switch (piece.copyStatus) {
                          @case ('COPIED') {
                            @if (previews()[key(item, piece)]; as url) {
                              <img class="piece__preview" [src]="url" [alt]="piece.name" />
                            }
                            <button mat-button type="button" (click)="open(item, piece)">
                              <mat-icon>open_in_new</mat-icon> Ouvrir
                            </button>
                          }
                          @case ('FAILED') {
                            <span class="piece__failed">Copie échouée : {{ piece.copyError || 'motif inconnu' }}</span>
                          }
                          @default {
                            <span class="besoins__note">En copie…</span>
                          }
                        }
                      </li>
                    }
                  </ul>
                }

                <div class="besoin__edit">
                  <mat-form-field appearance="outline">
                    <mat-label>Statut</mat-label>
                    <mat-select [(ngModel)]="draftStatus" name="draftStatus">
                      @for (s of statuses; track s) {
                        <mat-option [value]="s">{{ status(s) }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Feature</mat-label>
                    <input matInput [(ngModel)]="draftFeature" name="draftFeature" placeholder="F-210"
                      pattern="^ *([Ff]-[0-9]{1,4})? *$" />
                    <mat-error>De la forme F-123.</mat-error>
                  </mat-form-field>
                  <button mat-flat-button color="primary" type="button" [disabled]="saving() || !featureValid()"
                    (click)="save(item)">
                    Enregistrer
                  </button>
                </div>
              </div>
            }
          </article>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .besoins__filter {
      width: 200px;
      max-width: 100%;
    }

    .besoins__note {
      color: var(--cg-text-secondary);
    }

    .besoin {
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      margin-bottom: var(--cg-space-2);
    }

    .besoin--open {
      border-color: var(--cg-orange-2);
    }

    .besoin__head {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      width: 100%;
      min-height: 44px;
      padding: var(--cg-space-2);
      border: 0;
      background: none;
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }

    .besoin__head mat-icon,
    .besoin__kind {
      color: var(--cg-orange-2);
    }

    .besoin__kind {
      font-weight: 600;
    }

    .besoin__title {
      flex: 1;
      min-width: 0;
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .besoin__meta {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .besoin__detail {
      padding: 0 var(--cg-space-2) var(--cg-space-2);
    }

    .besoin__subtitle {
      margin: var(--cg-space-3) 0 var(--cg-space-1);
      font-size: 13px;
      font-weight: 600;
      color: var(--cg-text-secondary);
    }

    .besoin__words {
      margin: 0;
      padding: var(--cg-space-1) var(--cg-space-2);
      border-left: 2px solid var(--cg-orange-2);
      font-style: italic;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }

    .besoin__text {
      margin: 0;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }

    .besoin__pieces {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .piece {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) 0;
      border-bottom: 1px solid var(--cg-divider);
    }

    .piece__name {
      overflow-wrap: anywhere;
    }

    .piece__preview {
      display: block;
      max-width: 100%;
      max-height: 240px;
      border-radius: 4px;
      border: 1px solid var(--cg-divider);
    }

    .piece__failed {
      color: var(--cg-error);
      font-size: 13px;
    }

    .besoin__edit {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-3);
    }
  `,
})
export class AdminBesoinsComponent implements OnInit, OnDestroy {
  private readonly requests = inject(AppRequestService);
  private readonly snackBar = inject(MatSnackBar);

  readonly filters = BESOIN_FILTERS;
  readonly statuses = BESOIN_STATUSES;

  filter: AppRequestStatus | null = 'EN_ATTENTE';
  draftStatus: AppRequestStatus = 'EN_ATTENTE';
  draftFeature = '';

  readonly items = signal<AppRequestView[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly openId = signal<string | null>(null);
  /** Aperçus d'images, par « id/rang » : URL d'objet, libérées à la destruction. */
  readonly previews = signal<Record<string, string>>({});

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    Object.values(this.previews()).forEach((url) => URL.revokeObjectURL(url));
  }

  load(): void {
    this.loading.set(true);
    this.requests.list(this.filter).subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.snackBar.open('Impossible de charger les besoins.', 'Fermer', { duration: 4000 });
      },
    });
  }

  toggle(item: AppRequestView): void {
    if (this.openId() === item.id) {
      this.openId.set(null);
      return;
    }
    this.openId.set(item.id);
    this.draftStatus = item.status === 'PROPOSE' ? 'EN_ATTENTE' : item.status;
    this.draftFeature = item.featureRef ?? '';
    for (const piece of item.attachments) {
      if (piece.copyStatus === 'COPIED' && piece.contentType.startsWith('image/')
          && !this.previews()[this.key(item, piece)]) {
        this.requests.attachment(item.id, piece.position).subscribe({
          next: (blob) => this.previews.update((all) => ({ ...all, [this.key(item, piece)]: URL.createObjectURL(blob) })),
          error: () => undefined,
        });
      }
    }
  }

  featureValid(): boolean {
    const ref = this.draftFeature.trim();
    return ref === '' || FEATURE_REF.test(ref);
  }

  save(item: AppRequestView): void {
    if (!this.featureValid() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.requests.update(item.id, { status: this.draftStatus, featureRef: this.draftFeature.trim() }).subscribe({
      next: (view) => {
        this.saving.set(false);
        this.items.update((all) => all.map((i) => (i.id === view.id ? view : i)));
        this.snackBar.open('Besoin mis à jour.', 'Fermer', { duration: 3000 });
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.snackBar.open(err?.error?.message ?? 'Mise à jour refusée.', 'Fermer', { duration: 4000 });
      },
    });
  }

  /** Ouvre la pièce dans un nouvel onglet (lue avec l'authentification, puis servie en URL d'objet). */
  open(item: AppRequestView, piece: AppRequestAttachmentView): void {
    this.requests.attachment(item.id, piece.position).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.target = '_blank';
        link.rel = 'noopener';
        link.download = piece.contentType.startsWith('image/') || piece.contentType.startsWith('video/')
          || piece.contentType === 'application/pdf' ? '' : piece.name;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.snackBar.open('Pièce illisible.', 'Fermer', { duration: 4000 }),
    });
  }

  key(item: AppRequestView, piece: AppRequestAttachmentView): string {
    return `${item.id}/${piece.position}`;
  }

  kind(kind: string): string {
    return appRequestKindLabel(kind);
  }

  status(status: string): string {
    return appRequestStatusLabel(status);
  }

  size(bytes: number): string {
    return sizeLabel(bytes);
  }
}
