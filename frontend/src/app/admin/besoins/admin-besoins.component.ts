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

import {
  AppRequestAttachmentView,
  AppRequestKind,
  AppRequestStatus,
  AppRequestView,
} from '../../core/models/atelier.models';
import {
  AppRequestService,
  appRequestKindIcon,
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

/** Filtres de type (F-269) : un type, ou tous (`null`). */
export const BESOIN_KIND_FILTERS: { value: AppRequestKind | null; label: string }[] = [
  { value: null, label: 'Tous' },
  { value: 'BUG', label: 'Bugs' },
  { value: 'EVOLUTION', label: 'Évolutions' },
  { value: 'COMMANDE', label: 'Commandes' },
];

/** Longueur maximale d'une commande (même borne que le serveur). */
export const COMMAND_MAX_CHARS = 8000;

/** Statuts qu'un administrateur peut poser. */
export const BESOIN_STATUSES: AppRequestStatus[] = ['EN_ATTENTE', 'CADRE', 'REJETE', 'LIVRE'];

const FEATURE_REF = /^F-\d{1,4}$/i;

/**
 * Section **Besoins** de l'administration (F-205 / SF-205-02) : les bugs et évolutions de
 * l'application signalés depuis un terminal et enregistrés d'un clic — et, depuis F-269, les
 * **commandes pour Claude Code**, saisies ici ou dictées à l'agent, filtrables par type. On les filtre, on relit les mots
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
          Bugs et évolutions de l’application signalés depuis un terminal, et commandes pour Claude Code.
          Claude Code les relit par <code>/api/admin/app-requests</code> à chaque démarrage.
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <!-- F-269 — UNE COMMANDE POUR CLAUDE CODE, saisie directement : exécutée telle quelle au prochain
             démarrage de Claude Code. Le texte part mot pour mot. -->
        <section class="commande" aria-labelledby="commande-title">
          <h3 id="commande-title" class="besoin__subtitle">Nouvelle commande pour Claude Code</h3>
          <mat-form-field appearance="outline" class="commande__field">
            <mat-label>Commande, telle que Claude Code doit l’exécuter</mat-label>
            <textarea matInput [(ngModel)]="commandText" name="commandText" rows="3"
              [maxlength]="commandMax" placeholder="Ex. : lance les bancs Jarvis, 2 passages"></textarea>
            <mat-hint align="end">{{ commandText.length }} / {{ commandMax }}</mat-hint>
          </mat-form-field>
          <button mat-flat-button color="primary" type="button" class="commande__save"
            [disabled]="sendingCommand() || !commandText.trim()" (click)="saveCommand()">
            <mat-icon>terminal</mat-icon> Enregistrer
          </button>
        </section>

        <div class="besoins__filters">
          <mat-form-field appearance="outline" class="besoins__filter">
            <mat-label>Statut</mat-label>
            <mat-select [(ngModel)]="filter" name="filter" (selectionChange)="load()">
              @for (f of filters; track f.label) {
                <mat-option [value]="f.value">{{ f.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="besoins__filter">
            <mat-label>Type</mat-label>
            <mat-select [(ngModel)]="kindFilter" name="kindFilter" (selectionChange)="load()">
              @for (f of kindFilters; track f.label) {
                <mat-option [value]="f.value">{{ f.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>

        @if (loading()) {
          <p class="besoins__note">Chargement…</p>
        } @else if (!items().length) {
          <p class="besoins__note">Aucun besoin pour ce filtre.</p>
        }

        @for (item of items(); track item.id) {
          <article class="besoin" [class.besoin--open]="openId() === item.id">
            <button type="button" class="besoin__head" [attr.aria-expanded]="openId() === item.id" (click)="toggle(item)">
              <mat-icon aria-hidden="true">{{ icon(item.kind) }}</mat-icon>
              <span class="besoin__kind">{{ kind(item.kind) }}</span>
              <span class="besoin__title">{{ item.title }}</span>
              <span class="besoin__meta">
                {{ status(item.status) }}@if (item.featureRef) { · {{ item.featureRef }} }@if (item.complements?.length) { · {{ item.complements!.length }} complément{{ item.complements!.length > 1 ? 's' : '' }} } · {{ item.createdAt | date: 'short' }}
              </span>
            </button>

            @if (openId() === item.id) {
              <div class="besoin__detail">
                <h3 class="besoin__subtitle">{{ item.kind === 'COMMANDE' ? 'La commande (telle quelle)' : 'Ses mots' }}</h3>
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

                <!-- F-205 / SF-205-04 — LES COMPLÉMENTS, dans l'ordre : ce que le PO a ajouté ensuite. Les
                     mots d'origine, plus haut, ne sont jamais réécrits. -->
                @if (item.complements?.length) {
                  <h3 class="besoin__subtitle">Compléments ({{ item.complements!.length }})</h3>
                  <ol class="besoin__complements">
                    @for (c of item.complements!; track c.id) {
                      <li class="complement">
                        <p class="besoins__note">{{ (c.recordedAt || c.createdAt) | date: 'short' }}</p>
                        <blockquote class="besoin__words">{{ c.description }}</blockquote>
                        @if (c.reformulation) {
                          <p class="besoin__text"><strong>Reformulation à jour :</strong> {{ c.reformulation }}</p>
                        }
                        @if (c.observedContext) {
                          <p class="besoin__text"><strong>Observé :</strong> {{ c.observedContext }}</p>
                        }
                        @if (c.attachments.length) {
                          <ul class="besoin__pieces">
                            @for (piece of c.attachments; track piece.position) {
                              <li class="piece">
                                <span class="piece__name">{{ piece.name }} <span class="besoins__note">{{ size(piece.size) }}</span></span>
                                @switch (piece.copyStatus) {
                                  @case ('COPIED') {
                                    @if (previews()[key(c, piece)]; as url) {
                                      <img class="piece__preview" [src]="url" [alt]="piece.name" />
                                    }
                                    <button mat-button type="button" (click)="open(c, piece)">
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
                      </li>
                    }
                  </ol>
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
    .commande {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--cg-space-2);
      margin-bottom: var(--cg-space-3);
      padding-bottom: var(--cg-space-2);
      border-bottom: 1px solid var(--cg-divider);
    }

    .commande .besoin__subtitle {
      flex-basis: 100%;
      margin-top: 0;
    }

    .commande__field {
      flex: 1 1 320px;
      min-width: 0;
    }

    .commande__save {
      min-height: 44px;
      margin-top: var(--cg-space-1);
    }

    .besoins__filters {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
    }

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

    .besoin__complements {
      margin: 0;
      padding-left: var(--cg-space-3);
    }

    .complement {
      margin-bottom: var(--cg-space-2);
    }

    .complement > .besoins__note {
      margin: 0 0 var(--cg-space-1);
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

  readonly kindFilters = BESOIN_KIND_FILTERS;
  readonly commandMax = COMMAND_MAX_CHARS;

  filter: AppRequestStatus | null = 'EN_ATTENTE';
  kindFilter: AppRequestKind | null = null;
  commandText = '';
  draftStatus: AppRequestStatus = 'EN_ATTENTE';
  draftFeature = '';

  readonly items = signal<AppRequestView[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly sendingCommand = signal(false);
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
    this.requests.list(this.filter, this.kindFilter).subscribe({
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
    // Les pièces du besoin, puis celles de ses compléments (lues par l'id du complément).
    const owners: { id: string; attachments: AppRequestAttachmentView[] }[] = [item, ...(item.complements ?? [])];
    for (const owner of owners) {
      for (const piece of owner.attachments) {
        if (piece.copyStatus === 'COPIED' && piece.contentType.startsWith('image/')
            && !this.previews()[this.key(owner, piece)]) {
          this.requests.attachment(owner.id, piece.position).subscribe({
            next: (blob) => this.previews.update((all) => ({ ...all, [this.key(owner, piece)]: URL.createObjectURL(blob) })),
            error: () => undefined,
          });
        }
      }
    }
  }

  /**
   * [Enregistrer] une commande pour Claude Code (F-269) : le texte part tel quel ; en cas de refus, il
   * reste dans le champ.
   */
  saveCommand(): void {
    const text = this.commandText;
    if (!text.trim() || this.sendingCommand()) {
      return;
    }
    this.sendingCommand.set(true);
    this.requests.createCommand(text).subscribe({
      next: () => {
        this.sendingCommand.set(false);
        this.commandText = '';
        this.snackBar.open('Commande enregistrée — Claude Code l’exécutera à son prochain démarrage.', 'Fermer',
          { duration: 4000 });
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.sendingCommand.set(false);
        this.snackBar.open(err?.error?.message ?? 'Commande refusée.', 'Fermer', { duration: 4000 });
      },
    });
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
  open(item: { id: string }, piece: AppRequestAttachmentView): void {
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

  key(item: { id: string }, piece: AppRequestAttachmentView): string {
    return `${item.id}/${piece.position}`;
  }

  kind(kind: string): string {
    return appRequestKindLabel(kind);
  }

  icon(kind: string): string {
    return appRequestKindIcon(kind);
  }

  status(status: string): string {
    return appRequestStatusLabel(status);
  }

  size(bytes: number): string {
    return sizeLabel(bytes);
  }
}
